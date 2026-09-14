package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenWakeWord engine — on-device wake-word detection using a TFLite/ONNX model.
 */
object OpenWakeWordEngine {

    private const val TAG           = "AIRI_OWW"
    private const val PREFERRED_MODEL = "ok_airi.tflite"
    private const val LEGACY_MODEL    = "hey_airi.tflite"
    private const val FRAME_SAMPLES = 1280          // 80 ms at 16 kHz
    private const val SAMPLE_RATE   = 16_000
    private const val THRESHOLD     = 0.5f          // detection sensitivity

    data class Status(
        val ready:        Boolean,
        val modelSource:  String? = null,
        val reason:       String? = null
    )

    /** Check whether the model asset is present and the engine can be used. */
    fun status(context: Context): Status {
        val modelFile = modelFile(context)
        return when {
            modelFile != null && modelFile.exists() && modelFile.length() > 0 ->
                Status(ready = true, modelSource = modelFile.absolutePath)
            extractedFromAssets(context) != null ->
                Status(ready = true, modelSource = extractedFromAssets(context)?.absolutePath)
            else ->
                Status(
                    ready = false,
                    reason = "No ok_airi.tflite (or legacy hey_airi.tflite) found in assets/voice/. " +
                             "Add a model trained for the phrase Ok Airi."
                )
        }
    }

    /** Returns the on-disk TFLite model path ready for an interpreter, or null. */
    fun resolveModelFile(context: Context): File? {
        val f = modelFile(context)
        if (f != null && f.exists() && f.length() > 0L) return f
        return extractedFromAssets(context)
    }

    /** Number of samples per frame expected by the model. */
    val frameSamples: Int get() = FRAME_SAMPLES

    /** Required sample rate in Hz. */
    val sampleRate: Int get() = SAMPLE_RATE

    /** Detection threshold (0..1). Higher = fewer false positives, lower recall. */
    val threshold: Float get() = THRESHOLD

    fun normalizeFrame(frame: ShortArray): FloatArray {
        return FloatArray(frame.size) { i -> frame[i] / 32768f }
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun modelFile(context: Context): File? {
        val dir = File(context.applicationContext.filesDir, "voice")
        return listOf(PREFERRED_MODEL, LEGACY_MODEL)
            .map { File(dir, it) }
            .firstOrNull { it.exists() && it.length() > 0L }
    }

    private fun extractedFromAssets(context: Context): File? {
        val app = context.applicationContext
        val assetFiles = runCatching { app.assets.list("voice") }.getOrNull() ?: return null
        val assetName = when {
            PREFERRED_MODEL in assetFiles -> PREFERRED_MODEL
            LEGACY_MODEL in assetFiles -> LEGACY_MODEL
            else -> return null
        }

        val dest = File(app.filesDir, "voice/$assetName")
        if (dest.exists() && dest.length() > 0L) return dest // already extracted

        return try {
            dest.parentFile?.mkdirs()
            app.assets.open("voice/$assetName").use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            Log.i(TAG, "AIRI OWW_EXTRACTED model=${dest.absolutePath} size=${dest.length()}")
            dest
        } catch (t: Throwable) {
            Log.e(TAG, "OWW asset extraction failed: ${t.message}", t)
            null
        }
    }
}
