package com.airi.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.util.Log
import androidx.core.content.ContextCompat
import com.konovalov.vad.silero.Vad
import com.konovalov.vad.silero.VadSilero
import com.konovalov.vad.silero.config.FrameSize
import com.konovalov.vad.silero.config.Mode
import com.konovalov.vad.silero.config.SampleRate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.sqrt

/**
 * Production-grade full-duplex Voice Activity Detection (VAD) engine.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * DESIGN GOALS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  1. ZERO DOUBLE-FIRE: AtomicBoolean CAS ensures [onVoiceDetected] fires
 *     exactly once per session, regardless of how many consecutive speech
 *     frames the Silero model classifies as positive.
 *
 *  2. GUARANTEED MIC RELEASE BEFORE CALLER PROCEEDS: [stop] releases
 *     AudioRecord *synchronously* (not just in the coroutine's finally block)
 *     so callers can safely open a new AudioRecord immediately after [stop]
 *     returns — no race condition with VoskEngine's VOICE_RECOGNITION source.
 *
 *  3. ECHO CANCELLATION: Uses VOICE_COMMUNICATION audio source (the only
 *     source Android routes through the hardware AEC/NS pipeline). An
 *     AcousticEchoCanceler is additionally attached to the session so that
 *     TTS playback from the speaker is removed from the mic signal before
 *     the VAD ever sees it. This prevents the AI's own voice from triggering
 *     a false interruption (critical issue on speaker-phone / tablet configs).
 *
 *  4. NOISE FLOOR CALIBRATION: The first WARMUP_FRAMES (200 ms) of audio
 *     are sampled to compute the ambient RMS. If the room is extremely loud
 *     (sustained RMS > ambient threshold), a warning is logged. Future
 *     versions can use this value to dynamically adjust speechDurationMs.
 *
 *  5. NO MEMORY LEAKS: AudioRecord and VadSilero are ALWAYS released in the
 *     coroutine's finally block. The synchronous release in [stop] also
 *     nulls the reference atomically to prevent double-free.
 *
 *  6. LIFECYCLE SAFE: The caller cancels the scope on app pause / navigate-
 *     away. The finally block runs unconditionally on cancellation, releasing
 *     the microphone even under forced stop.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * AUDIO SOURCE: VOICE_COMMUNICATION (not MIC)
 * ─────────────────────────────────────────────────────────────────────────
 *
 * Unlike MediaRecorder.AudioSource.MIC, VOICE_COMMUNICATION activates
 * Android's voice-call DSP path, which includes:
 *   - Hardware AEC (removes the TTS speaker signal from the mic)
 *   - Noise suppression
 *   - Automatic gain control
 *
 * VOICE_COMMUNICATION ≠ VOICE_RECOGNITION (VoskEngine's source), so
 * there is no audio-source conflict. Both sources are separate DSP paths
 * into the same physical microphone hardware.
 *
 * The VAD (VOICE_COMMUNICATION) runs only while TTS is active.
 * VoskEngine (VOICE_RECOGNITION) starts only AFTER the VAD's AudioRecord
 * has been released via [stop]. They are NEVER open simultaneously.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * LIFECYCLE
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   1. Create instance per TTS turn (do NOT reuse after [stop]).
 *   2. Call [start] when the first TTS chunk begins playing.
 *   3. [onVoiceDetected] fires on Main, at most once.
 *   4. [stop] is idempotent; call it from natural TTS end or lifecycle pause.
 *   5. Discard the instance.
 */
class FullDuplexVadEngine(
    private val context: Context,
    /**
     * Called on the MAIN thread when Silero confirms ≥ SPEECH_MS of
     * continuous speech from the user. At most once per session.
     */
    private val onVoiceDetected: () -> Unit,
    /**
     * Called on the MAIN thread when the VAD loop exits for ANY reason
     * other than detection (cancelled, timed-out, error). Not called if
     * [onVoiceDetected] already fired.
     */
    private val onStopped: (reason: String) -> Unit = {}
) {

    // ─────────────────────────────────────────────────────────────────────
    // Configuration
    //
    //   SAMPLE_RATE_16K  — Silero VAD's native rate (no resampling)
    //   FRAME_SIZE_320   — 320 samples = 20 ms @ 16 kHz
    //   VERY_AGGRESSIVE  — max noise rejection (important near TTS speaker)
    //   SPEECH_MS = 80   — 4 frames of speech before triggering. Too low →
    //                      false positives from plosives / TTS breath sounds.
    //                      Too high → noticeable latency before TTS stops.
    //   SILENCE_MS = 300 — unused for single-fire, kept for Vad config API.
    //   WARMUP_FRAMES    — frames sampled before arming (noise calibration).
    //   MAX_SESSION_MS   — hard timeout; prevents eternal mic lock if TTS
    //                      never completes (e.g. extremely long monologue).
    // ─────────────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "AIRI_VAD"

        const val SAMPLE_RATE_HZ   = 16_000
        const val FRAME_SAMPLES    = 320          // 20 ms @ 16 kHz
        const val FRAME_BYTES      = FRAME_SAMPLES * 2  // PCM_16BIT = 2 bytes
        const val SPEECH_MS        = 80
        const val SILENCE_MS       = 300
        const val WARMUP_FRAMES    = 10           // 200 ms noise calibration
        const val MAX_SESSION_MS   = 50_000L      // 50 s safety timeout

        // Ambient RMS above this level warrants a log warning.
        // 0–32767 range for PCM_16BIT. ~800 = fan / quiet HVAC.
        const val NOISY_ENV_RMS    = 1_200
    }

    // ── State (AtomicBoolean for lock-free cross-thread coordination) ─────

    /**
     * CAS gate — transitions false → true exactly once.
     * Prevents double-fire from consecutive Silero "speech" frames AND from
     * concurrent calls (e.g. stopVad races against a late detection frame).
     */
    private val detected = AtomicBoolean(false)

    /**
     * Set to true by [stop] before releasing resources. The capture loop
     * checks this every frame to break cleanly.
     */
    private val stopped = AtomicBoolean(false)

    // ── AudioRecord (volatile for cross-thread visibility in stop()) ──────

    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var aec: AcousticEchoCanceler? = null
    @Volatile private var captureJob: Job? = null

    // ─────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Starts the VAD capture loop on [Dispatchers.IO].
     *
     * RECORD_AUDIO permission is verified before AudioRecord is opened.
     * Double-start is silently ignored (logged as warning).
     * AudioRecord init failure is reported via [onStopped] — NOT a crash.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (captureJob != null) {
            Log.w(TAG, "VAD_START_IGNORED reason=already_running")
            return
        }
        if (stopped.get()) {
            Log.w(TAG, "VAD_START_IGNORED reason=already_stopped")
            return
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Log.w(TAG, "VAD_START_FAILED reason=no_record_audio_permission")
            fireOnStopped(scope, "no_record_audio_permission")
            return
        }

        // ── AudioRecord ──────────────────────────────────────────────────
        // Source: VOICE_COMMUNICATION activates the AEC DSP path on the
        // hardware so TTS speaker output is cancelled before the mic
        // signal reaches our VAD. Completely separate from VoskEngine's
        // VOICE_RECOGNITION source — no hardware conflict.
        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuf <= 0) {
            Log.w(TAG, "VAD_START_FAILED reason=getMinBufferSize=$minBuf")
            fireOnStopped(scope, "audio_record_unavailable")
            return
        }
        val bufSize = maxOf(minBuf, FRAME_BYTES * 8)

        val rec = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufSize
            )
        } catch (t: Throwable) {
            Log.w(TAG, "VAD_START_FAILED reason=AudioRecord_create: ${t.message}")
            fireOnStopped(scope, "audio_record_create_failed")
            return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            try { rec.release() } catch (_: Throwable) {}
            Log.w(TAG, "VAD_START_FAILED reason=AudioRecord_uninitialized")
            fireOnStopped(scope, "audio_record_uninitialized")
            return
        }
        audioRecord = rec

        // ── Software AEC via AudioEffect ─────────────────────────────────
        // VOICE_COMMUNICATION source already triggers hardware AEC.
        // Additionally attaching AcousticEchoCanceler gives us software-
        // layer echo suppression on devices where hardware AEC is absent.
        if (AcousticEchoCanceler.isAvailable()) {
            val effect = try {
                AcousticEchoCanceler.create(rec.audioSessionId)
            } catch (_: Throwable) { null }
            if (effect != null) {
                effect.enabled = true
                aec = effect
                Log.i(TAG, "AIRI_PROOF AEC_ENABLED sessionId=${rec.audioSessionId}")
            } else {
                Log.i(TAG, "AIRI_PROOF AEC_UNAVAILABLE (hardware AEC still active via VOICE_COMMUNICATION)")
            }
        }

        // ── Silero VAD model ─────────────────────────────────────────────
        // ONNX model is loaded from the AAR's assets (~2 MB). Blocking IO —
        // must run on Dispatchers.IO (inside the coroutine below).
        Log.i(TAG, "AIRI_PROOF VAD_STARTING speechMs=$SPEECH_MS mode=VERY_AGGRESSIVE source=VOICE_COMMUNICATION")

        captureJob = scope.launch(Dispatchers.IO) {
            var vadInstance: VadSilero? = null
            var stopReason = "normal"
            try {
                // Load Silero model on IO thread (avoids main-thread jank)
                vadInstance = try {
                    Vad.builder()
                        .setContext(context.applicationContext)
                        .setSampleRate(SampleRate.SAMPLE_RATE_16K)
                        .setFrameSize(FrameSize.FRAME_SIZE_320)
                        .setMode(Mode.VERY_AGGRESSIVE)
                        .setSpeechDurationMs(SPEECH_MS)
                        .setSilenceDurationMs(SILENCE_MS)
                        .build()
                } catch (t: Throwable) {
                    Log.w(TAG, "VAD_MODEL_LOAD_FAILED: ${t.message}")
                    stopReason = "model_load_failed: ${t.message}"
                    return@launch
                }

                rec.startRecording()
                Log.i(TAG, "AIRI_PROOF VAD_CAPTURE_STARTED bufSize=$bufSize")

                val byteFrame = ByteArray(FRAME_BYTES)
                val shortFrame = ShortArray(FRAME_SAMPLES)
                val deadline = System.currentTimeMillis() + MAX_SESSION_MS

                // ── Noise floor calibration (warmup) ─────────────────────
                // Sample WARMUP_FRAMES of audio before arming the detector.
                // Computes ambient RMS for diagnostics and future adaptive
                // threshold support. The VAD is NOT consulted during warmup.
                var sumSq = 0L
                var warmupCount = 0
                while (warmupCount < WARMUP_FRAMES && isActive && !stopped.get()) {
                    val n = readFullFrame(rec, byteFrame)
                    if (n < FRAME_BYTES) break
                    toShortFrame(byteFrame, shortFrame)
                    for (s in shortFrame) sumSq += s.toLong() * s.toLong()
                    warmupCount++
                }
                val ambientRms = if (warmupCount > 0)
                    sqrt((sumSq / (warmupCount.toLong() * FRAME_SAMPLES)).toDouble()).toInt()
                else 0
                Log.i(TAG, "AIRI_PROOF VAD_NOISE_FLOOR ambientRms=$ambientRms warmupFrames=$warmupCount noisyEnv=${ambientRms > NOISY_ENV_RMS}")

                // ── Detection loop ───────────────────────────────────────
                while (isActive && !stopped.get() && !detected.get() &&
                    System.currentTimeMillis() < deadline
                ) {
                    val n = readFullFrame(rec, byteFrame)
                    if (n < 0) {
                        // ERROR_DEAD_OBJECT (-5) or ERROR_INVALID_OPERATION (-3):
                        // AudioRecord was released externally via stop() — clean exit.
                        stopReason = "audio_record_error_$n"
                        Log.i(TAG, "VAD_READ_ERROR code=$n → exiting loop")
                        break
                    }
                    if (n < FRAME_BYTES) continue

                    toShortFrame(byteFrame, shortFrame)

                    val isSpeech = try {
                        vadInstance.isSpeech(shortFrame)
                    } catch (t: Throwable) {
                        Log.w(TAG, "VAD_INFERENCE_ERROR: ${t.message}")
                        false
                    }

                    if (isSpeech) {
                        // CAS: only the FIRST positive frame fires the callback.
                        // All subsequent frames from the same speech burst are dropped.
                        if (detected.compareAndSet(false, true)) {
                            stopReason = "voice_detected"
                            Log.i(TAG, "AIRI_PROOF VAD_SPEECH_CONFIRMED → firing onVoiceDetected on Main")
                            withContext(Dispatchers.Main) {
                                onVoiceDetected()
                            }
                            break
                        }
                    }
                }

                if (!detected.get() && System.currentTimeMillis() >= deadline) {
                    stopReason = "timeout_${MAX_SESSION_MS}ms"
                    Log.i(TAG, "AIRI_PROOF VAD_TIMEOUT — TTS must have finished without interruption")
                }

            } catch (e: CancellationException) {
                stopReason = "cancelled"
                Log.i(TAG, "VAD_CAPTURE_CANCELLED (scope cancelled or stop() called)")
                throw e  // rethrow so coroutines framework knows this was cancellation
            } catch (t: Throwable) {
                stopReason = "exception: ${t.message}"
                Log.w(TAG, "VAD_CAPTURE_EXCEPTION: ${t.message}", t)
            } finally {
                // ── GUARANTEED CLEANUP ───────────────────────────────────
                // Runs on cancellation, normal exit, or exception.
                // audioRecord may already be null if stop() released it
                // synchronously; in that case these are safe no-ops.
                val r = audioRecord
                audioRecord = null
                try { r?.stop() } catch (_: Throwable) {}
                try { r?.release() } catch (_: Throwable) {}

                try { aec?.release() } catch (_: Throwable) {}
                aec = null

                try { vadInstance?.close() } catch (_: Throwable) {}

                Log.i(TAG, "AIRI_PROOF VAD_CAPTURE_STOPPED reason=$stopReason detected=${detected.get()}")

                // Fire onStopped only if we exited without detection
                if (!detected.get()) {
                    withContext(Dispatchers.Main.immediate) {
                        onStopped(stopReason)
                    }
                }
            }
        }
    }

    /**
     * Stops the VAD loop. IDEMPOTENT — safe to call multiple times from
     * any thread.
     *
     * Critically: releases AudioRecord SYNCHRONOUSLY before returning.
     * This is the guarantee that VoiceManager relies on before opening
     * a new AudioRecord (VoskEngine's VOICE_RECOGNITION source). The
     * coroutine's finally block will see audioRecord == null and skip.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) {
            // Already stopped — idempotent, no work needed.
            return
        }

        // ── Synchronous mic release ──────────────────────────────────────
        // Null-and-swap atomically so the finally block (running concurrently
        // on Dispatchers.IO) gets null and performs no additional release.
        val r = audioRecord
        audioRecord = null
        try { r?.stop() } catch (_: Throwable) {}
        try { r?.release() } catch (_: Throwable) {}

        val effect = aec
        aec = null
        try { effect?.release() } catch (_: Throwable) {}

        // Cancel the coroutine — rec.read() on IO thread returns
        // ERROR_DEAD_OBJECT (-5), causing the loop to break immediately.
        captureJob?.cancel()
        captureJob = null

        Log.i(TAG, "AIRI_PROOF VAD_STOP_SYNC mic_released=true")
    }

    /** True after [onVoiceDetected] has fired. */
    fun hasDetected(): Boolean = detected.get()

    /** True after [stop] has been called or detection fired. */
    fun isStopped(): Boolean = stopped.get() || detected.get()

    // ─────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────

    /**
     * Reads exactly [FRAME_BYTES] bytes from [rec] via a partial-read loop.
     * Returns [FRAME_BYTES] on success, a negative error code on failure,
     * or a positive value < [FRAME_BYTES] if stopped/cancelled mid-read.
     */
    private fun readFullFrame(rec: AudioRecord, buf: ByteArray): Int {
        var offset = 0
        while (offset < FRAME_BYTES) {
            if (stopped.get()) return offset
            val n = rec.read(buf, offset, FRAME_BYTES - offset)
            when {
                n > 0  -> offset += n
                n == 0 -> continue          // rare: no data yet
                else   -> return n          // error code (negative)
            }
        }
        return offset
    }

    /**
     * Converts a little-endian PCM_16BIT [ByteArray] to a [ShortArray]
     * in-place. No allocation — both buffers are reused across frames.
     */
    private fun toShortFrame(src: ByteArray, dst: ShortArray) {
        for (i in dst.indices) {
            val lo = src[i * 2].toInt() and 0xFF
            val hi = src[i * 2 + 1].toInt()
            dst[i] = ((hi shl 8) or lo).toShort()
        }
    }

    private fun fireOnStopped(scope: CoroutineScope, reason: String) {
        scope.launch(Dispatchers.Main.immediate) { onStopped(reason) }
    }
}
