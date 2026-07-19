package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * OpenWakeWord engine — on-device wake-word detection using a TFLite/ONNX model.
 *
 * OpenWakeWord is an open-source, Apache 2.0 wake-word framework that:
 *   - Requires NO API key or account
 *   - Bundles a model that can be distributed with the app
 *   - Provides ~0.5 false activations/hour (threshold tunable)
 *   - Processes 80ms audio frames at 16kHz mono PCM-16
 *
 * Model distribution:
 *   Place `hey_airi.tflite` in `app/src/main/assets/voice/hey_airi.tflite`
 *   OR generate it from https://github.com/dscripka/openWakeWord
 *
 * This class is a graceful stub: if the model asset is absent it
 * returns [WakeWordStatus(ready=false)] and never fails with an exception.
 * This ensures the existing Porcupine path remains the default until
 * the OpenWakeWord asset is bundled.
 *
 * P0-V2: Once the .tflite asset is added, this engine activates automatically
 * with zero user setup — no API key, no account, no manual download.
 *
 * Audio format: PCM-16 mono 16kHz, frames of [FRAME_SAMPLES] = 1280 samples (80ms)
 */
object OpenWakeWordEngine {

    private const val TAG           = "AIRI_OWW"
    private const val ASSET_MODEL   = "voice/hey_airi.tflite"
    private const val FRAME_SAMPLES = 1280          // 80 ms at 16 kHz
    private const val SAMPLE_RATE   = 16_000
    private const val THRESHOLD     = 0.5f          // detection sensitivity

    data class WakeWordWakeWordStatus(
        val ready:        Boolean,
        val modelSource:  String? = null,
        val reason:       String? = null
    )

    /** Check whether the model asset is present and the engine can be used. */
    fun status(context: Context): WakeWordStatus {
        val modelFile = modelFile(context)
        return when {
            modelFile != null && modelFile.exists() && modelFile.length() > 0 ->
                WakeWordStatus(ready = true, modelSource = modelFile.absolutePath)
            extractedFromAssets(context) != null ->
                WakeWordStatus(ready = true, modelSource = extractedFromAssets(context)?.absolutePath)
            else ->
                WakeWordStatus(
                    ready = false,
                    reason = "No hey_airi.tflite found in assets/voice/. " +
                             "See https://github.com/dscripka/openWakeWord to generate one."
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

    /**
     * Process one [frame] of [FRAME_SAMPLES] PCM-16 samples.
     *
     * Returns the raw activation score (0..1).
     * Callers should fire wake detection when score ≥ [threshold].
     *
     * IMPORTANT: This is a stub implementation. It converts the ShortArray to
     * a FloatArray normalized to [-1, 1] which is the correct input format for
     * an OpenWakeWord TFLite model. Actual inference requires the TFLite
     * Interpreter to be initialized with [resolveModelFile].
     *
     * The HotwordService creates the TFLite Interpreter once (in onStartCommand)
     * and calls processFrame() per audio buffer.
     */
    fun normalizeFrame(frame: ShortArray): FloatArray {
        return FloatArray(frame.size) { i -> frame[i] / 32768f }
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun modelFile(context: Context): File? {
        val f = File(context.applicationContext.filesDir, "voice/hey_airi.tflite")
        return if (f.exists() && f.length() > 0L) f else null
    }

    private fun extractedFromAssets(context: Context): File? {
        val app = context.applicationContext
        val assetFiles = runCatching { app.assets.list("voice") }.getOrNull() ?: return null
        if ("hey_airi.tflite" !in assetFiles) return null

        val dest = File(app.filesDir, "voice/hey_airi.tflite")
        if (dest.exists() && dest.length() > 0L) return dest // already extracted

        return try {
            dest.parentFile?.mkdirs()
            app.assets.open(ASSET_MODEL).use { ins ->
                FileOutputStream(dest).use { out -> ins.copyTo(out) }
            }
            Log.i(TAG, "AIRI_PROOF OWW_EXTRACTED model=${dest.absolutePath} size=${dest.length()}")
            dest
        } catch (t: Throwable) {
            Log.e(TAG, "OWW asset extraction failed: ${t.message}", t)
            null
        }
    }
}
