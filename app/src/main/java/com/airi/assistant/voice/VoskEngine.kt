package com.airi.assistant.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer

/**
 * One-shot streaming speech-to-text driven by a Vosk [Model] loaded by
 * [VoskModelManager]. Runs the AudioRecord loop on a background coroutine
 * and surfaces partial + final results on the main thread.
 *
 * Usage:
 * ```
 * val engine = VoskEngine(context, model)
 * engine.start(scope, onPartial = {...}, onFinal = {...}, onError = {...})
 * // …later…
 * engine.stop()                     // flushes a final result
 * engine.release()                  // frees Vosk + AudioRecord
 * ```
 *
 * Lifecycle is single-shot: do not call [start] twice on the same instance —
 * create a fresh one. [stop] is idempotent.
 *
 * No internet, no Google APIs — every byte stays on the device.
 *
 * Thread-safety:
 *   All state-mutating entry points ([start], [release]) synchronize on
 *   [lifecycleLock] to prevent a TOCTOU race between concurrent callers.
 *   Without the lock, rapid microphone-interruption sequences can produce two
 *   overlapping AudioRecord loops:
 *     Thread A reads captureJob==null → Thread B reads captureJob==null →
 *     both pass the guard → two AudioRecord+Recognizer instances spawn,
 *     Thread B's captureJob silently overwrites Thread A's, leaking A's job.
 */
class VoskEngine(
    private val context: Context,
    private val model: Model
) {
    private val lifecycleLock = Any()

    private val sampleRate    = 16_000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat   = AudioFormat.ENCODING_PCM_16BIT

    @Volatile private var captureJob:  Job?         = null
    @Volatile private var audioRecord: AudioRecord? = null
    @Volatile private var recognizer:  Recognizer?  = null
    @Volatile private var stopRequested = false

    @SuppressLint("MissingPermission")
    fun start(
        scope:     CoroutineScope,
        onPartial: (String) -> Unit = {},
        onFinal:   (String) -> Unit,
        onError:   (String) -> Unit
    ) {
        // ── All setup and captureJob assignment inside lifecycleLock ──────────
        // This block must not block for long (it does no I/O), so holding the
        // lock across AudioRecord construction is acceptable.
        val launchParams: Triple<AudioRecord, Recognizer, Int>?
        synchronized(lifecycleLock) {
            if (captureJob != null) {
                onError("vosk_already_running")
                return
            }
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
                onError("missing_record_audio_permission")
                return
            }

            val minBuf = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBuf <= 0) {
                onError("audio_record_not_available")
                return
            }
            val bufSize = minBuf * 2

            val rec = try {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    sampleRate, channelConfig, audioFormat, bufSize
                )
            } catch (t: Throwable) {
                onError("audio_record_create_failed: ${t.message}")
                return
            }
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                try { rec.release() } catch (_: Throwable) {}
                onError("audio_record_uninitialized")
                return
            }
            audioRecord = rec

            val r = try {
                Recognizer(model, sampleRate.toFloat())
            } catch (t: Throwable) {
                try { rec.release() } catch (_: Throwable) {}
                audioRecord = null
                onError("vosk_recognizer_create_failed: ${t.message}")
                return
            }
            recognizer   = r
            stopRequested = false
            // Mark the slot as taken before we leave the lock, so a second
            // concurrent start() sees a non-null captureJob immediately.
            captureJob    = Job()
            launchParams  = Triple(rec, r, bufSize)
        }

        // ── Launch the coroutine outside the lock ─────────────────────────────
        // The sentinel Job above is replaced by the real launched Job.
        val (rec, r, bufSize) = launchParams ?: return
        val buf = ByteArray(bufSize)
        captureJob = scope.launch(Dispatchers.IO) {
            try {
                rec.startRecording()
                while (!stopRequested && isActive) {
                    val read = rec.read(buf, 0, buf.size)
                    if (read <= 0) continue
                    val finalSegment = r.acceptWaveForm(buf, read)
                    if (finalSegment) {
                        val text = parseText(r.result)
                        if (text.isNotBlank()) withContext(Dispatchers.Main) { onFinal(text) }
                    } else {
                        val partial = parsePartial(r.partialResult)
                        if (partial.isNotBlank()) withContext(Dispatchers.Main) { onPartial(partial) }
                    }
                }
                // Flush whatever's left.
                val tail = parseText(r.finalResult)
                if (tail.isNotBlank()) withContext(Dispatchers.Main) { onFinal(tail) }
            } catch (t: Throwable) {
                Log.w(TAG, "Vosk capture loop failed: ${t.message}", t)
                withContext(Dispatchers.Main) { onError("vosk_capture_failed: ${t.message}") }
            } finally {
                try { rec.stop() } catch (_: Throwable) {}
            }
        }
    }

    /** Marks the capture loop for stop. The final result is delivered via the onFinal callback. */
    fun stop() {
        stopRequested = true
    }

    /**
     * Releases the AudioRecord and Vosk recognizer. Safe to call multiple times.
     * Synchronized so concurrent release() + start() cannot interleave.
     */
    fun release() {
        synchronized(lifecycleLock) {
            stopRequested = true
            captureJob?.cancel()
            captureJob = null
            try { audioRecord?.release() } catch (_: Throwable) {}
            audioRecord = null
            try { recognizer?.close() } catch (_: Throwable) {}
            recognizer = null
        }
    }

    private fun parseText(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try { JSONObject(json).optString("text").orEmpty().trim() } catch (_: Throwable) { "" }
    }

    private fun parsePartial(json: String?): String {
        if (json.isNullOrBlank()) return ""
        return try { JSONObject(json).optString("partial").orEmpty().trim() } catch (_: Throwable) { "" }
    }

    private companion object {
        const val TAG = "AIRI_VOSK"
    }
}
