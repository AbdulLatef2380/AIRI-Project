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
 * Uses an RMS-energy detector with noise-floor calibration. The neural
 * Silero ONNX VAD dependency was removed to avoid a Kotlin stdlib version
 * conflict (Silero 2.0.x requires Kotlin 2.x; this project targets 1.9.x).
 * The RMS approach provides similar latency (<20 ms frame) with no extra
 * AAR dependency and no ONNX runtime overhead on the device.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * DESIGN GOALS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *  1. ZERO DOUBLE-FIRE: AtomicBoolean CAS ensures [onVoiceDetected] fires
 *     exactly once per session, regardless of how many consecutive speech
 *     frames the RMS model classifies as positive.
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
 *     are sampled to compute the ambient RMS. The adaptive speech threshold
 *     is set to max(BASE_SPEECH_THRESHOLD, ambientRms * 2.5) so that the
 *     detector is robust against varying background noise levels.
 *
 *  5. NO MEMORY LEAKS: AudioRecord is ALWAYS released in the coroutine's
 *     finally block. The synchronous release in [stop] also nulls the
 *     reference atomically to prevent double-free.
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
     * Called on the MAIN thread when the VAD confirms ≥ SPEECH_FRAMES of
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
    //   SAMPLE_RATE_16K  — compatible with the original Silero native rate
    //   FRAME_SIZE_320   — 320 samples = 20 ms @ 16 kHz
    //   BASE_SPEECH_RMS  — minimum absolute RMS for speech (quiet whisper ≈ 400)
    //   SPEECH_FRAMES    — consecutive frames above threshold before triggering
    //                      (4 frames = 80 ms, same as Silero config speechMs)
    //   WARMUP_FRAMES    — frames sampled before arming (noise calibration)
    //   MAX_SESSION_MS   — hard timeout; prevents eternal mic lock
    // ─────────────────────────────────────────────────────────────────────

    private companion object {
        const val TAG = "AIRI_VAD"

        const val SAMPLE_RATE_HZ   = 16_000
        const val FRAME_SAMPLES    = 320          // 20 ms @ 16 kHz
        const val FRAME_BYTES      = FRAME_SAMPLES * 2  // PCM_16BIT = 2 bytes
        const val BASE_SPEECH_RMS  = 500          // ~quiet speech, PCM_16BIT range 0-32767
        const val SPEECH_FRAMES    = 4            // 4 × 20 ms = 80 ms continuous speech
        const val WARMUP_FRAMES    = 10           // 200 ms noise calibration
        const val MAX_SESSION_MS   = 50_000L      // 50 s safety timeout

        // Ambient RMS above this level warrants a log warning.
        const val NOISY_ENV_RMS    = 1_200
    }

    // ── State (AtomicBoolean for lock-free cross-thread coordination) ─────

    /**
     * CAS gate — transitions false → true exactly once.
     * Prevents double-fire from consecutive "speech" frames AND from
     * concurrent calls (e.g. stop() races against a late detection frame).
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
        if (AcousticEchoCanceler.isAvailable()) {
            val effect = try {
                AcousticEchoCanceler.create(rec.audioSessionId)
            } catch (_: Throwable) { null }
            if (effect != null) {
                effect.enabled = true
                aec = effect
                Log.i(TAG, "AIRI AEC_ENABLED sessionId=${rec.audioSessionId}")
            } else {
                Log.i(TAG, "AIRI AEC_UNAVAILABLE (hardware AEC still active via VOICE_COMMUNICATION)")
            }
        }

        Log.i(TAG, "AIRI VAD_STARTING speechFrames=$SPEECH_FRAMES baseRms=$BASE_SPEECH_RMS source=VOICE_COMMUNICATION")

        captureJob = scope.launch(Dispatchers.IO) {
            var stopReason = "normal"
            try {
                rec.startRecording()
                Log.i(TAG, "AIRI VAD_CAPTURE_STARTED bufSize=$bufSize")

                val byteFrame = ByteArray(FRAME_BYTES)
                val shortFrame = ShortArray(FRAME_SAMPLES)
                val deadline = System.currentTimeMillis() + MAX_SESSION_MS

                // ── Noise floor calibration (warmup) ─────────────────────
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

                // Adaptive threshold: at least BASE_SPEECH_RMS, or 2.5× ambient
                val speechThreshold = maxOf(BASE_SPEECH_RMS, (ambientRms * 2.5).toInt())

                Log.i(TAG, "AIRI VAD_NOISE_FLOOR ambientRms=$ambientRms speechThreshold=$speechThreshold noisyEnv=${ambientRms > NOISY_ENV_RMS}")

                // ── Detection loop ───────────────────────────────────────
                var consecutiveSpeechFrames = 0
                while (isActive && !stopped.get() && !detected.get() &&
                    System.currentTimeMillis() < deadline
                ) {
                    val n = readFullFrame(rec, byteFrame)
                    if (n < 0) {
                        stopReason = "audio_record_error_$n"
                        Log.i(TAG, "VAD_READ_ERROR code=$n → exiting loop")
                        break
                    }
                    if (n < FRAME_BYTES) continue

                    toShortFrame(byteFrame, shortFrame)

                    val frameRms = computeRms(shortFrame)
                    val isSpeech = frameRms > speechThreshold

                    if (isSpeech) {
                        consecutiveSpeechFrames++
                        if (consecutiveSpeechFrames >= SPEECH_FRAMES) {
                            // CAS: only the FIRST confirmed speech fires the callback.
                            if (detected.compareAndSet(false, true)) {
                                stopReason = "voice_detected"
                                Log.i(TAG, "AIRI VAD_SPEECH_CONFIRMED rms=$frameRms threshold=$speechThreshold → firing onVoiceDetected on Main")
                                withContext(Dispatchers.Main) {
                                    onVoiceDetected()
                                }
                                break
                            }
                        }
                    } else {
                        consecutiveSpeechFrames = 0
                    }
                }

                if (!detected.get() && System.currentTimeMillis() >= deadline) {
                    stopReason = "timeout_${MAX_SESSION_MS}ms"
                    Log.i(TAG, "AIRI VAD_TIMEOUT — TTS must have finished without interruption")
                }

            } catch (e: CancellationException) {
                stopReason = "cancelled"
                Log.i(TAG, "VAD_CAPTURE_CANCELLED (scope cancelled or stop() called)")
                throw e
            } catch (t: Throwable) {
                stopReason = "exception: ${t.message}"
                Log.w(TAG, "VAD_CAPTURE_EXCEPTION: ${t.message}", t)
            } finally {
                // ── GUARANTEED CLEANUP ───────────────────────────────────
                val r = audioRecord
                audioRecord = null
                try { r?.stop() } catch (_: Throwable) {}
                try { r?.release() } catch (_: Throwable) {}

                try { aec?.release() } catch (_: Throwable) {}
                aec = null

                Log.i(TAG, "AIRI VAD_CAPTURE_STOPPED reason=$stopReason detected=${detected.get()}")

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
     * Releases AudioRecord SYNCHRONOUSLY before returning so that
     * VoiceManager can safely open a new AudioRecord immediately after.
     */
    fun stop() {
        if (!stopped.compareAndSet(false, true)) return

        val r = audioRecord
        audioRecord = null
        try { r?.stop() } catch (_: Throwable) {}
        try { r?.release() } catch (_: Throwable) {}

        val effect = aec
        aec = null
        try { effect?.release() } catch (_: Throwable) {}

        captureJob?.cancel()
        captureJob = null

        Log.i(TAG, "AIRI VAD_STOP_SYNC mic_released=true")
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
                n == 0 -> continue
                else   -> return n
            }
        }
        return offset
    }

    /**
     * Converts a little-endian PCM_16BIT [ByteArray] to a [ShortArray].
     * No allocation — both buffers are reused across frames.
     */
    private fun toShortFrame(src: ByteArray, dst: ShortArray) {
        for (i in dst.indices) {
            val lo = src[i * 2].toInt() and 0xFF
            val hi = src[i * 2 + 1].toInt()
            dst[i] = ((hi shl 8) or lo).toShort()
        }
    }

    /**
     * Computes the root-mean-square energy of a PCM_16BIT frame.
     * Range: 0 (silence) to 32767 (full-scale).
     */
    private fun computeRms(frame: ShortArray): Int {
        var sumSq = 0L
        for (s in frame) sumSq += s.toLong() * s.toLong()
        return sqrt(sumSq.toDouble() / frame.size).toInt()
    }

    private fun fireOnStopped(scope: CoroutineScope, reason: String) {
        scope.launch(Dispatchers.Main.immediate) { onStopped(reason) }
    }
}
