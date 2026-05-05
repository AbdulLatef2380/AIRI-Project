package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import org.vosk.Model
import org.vosk.Recognizer
import org.json.JSONObject
import java.io.Closeable

/**
 * InternalWakeWordEngine — fully on-device keyword spotting using Vosk.
 *
 * ── DESIGN ─────────────────────────────────────────────────────────────────
 *
 *   Uses a grammar-constrained Vosk [Recognizer] limited to the vocabulary
 *   `["hey airi", "hey airy", "[unk]"]` to detect the wake phrase.
 *
 *   No external API keys. No proprietary SDK. No network connectivity.
 *   The only requirement is a Vosk model installed via [VoskModelManager].
 *
 * ── AUDIO FORMAT ─────────────────────────────────────────────────────────
 *
 *   Expects 16 kHz mono PCM-16 LE samples (same format as [VoskEngine]).
 *   AudioRecord frames are converted to byte[] and fed to Vosk's streaming
 *   recognizer via [processFrame].
 *
 * ── DETECTION ────────────────────────────────────────────────────────────
 *
 *   On each partial result the engine checks whether the text contains
 *   "hey airi". If yes, [onWakeDetected] is called and the recognizer is
 *   reset so it's ready for the next activation.
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────
 *
 *     val engine = InternalWakeWordEngine(model) { fireWake() }
 *     // … in AudioRecord loop …
 *     engine.processFrame(shortArray, nRead)
 *     // … on service destroy …
 *     engine.close()
 */
class InternalWakeWordEngine(
    private val model:            Model,
    private val onWakeDetected:   () -> Unit,
    private val sensitivity:      Float = 0.6f
) : Closeable {

    private val TAG         = "AIRI_VOICE"
    private val WAKE_PHRASE = "hey airi"

    /**
     * Grammar-constrained Vosk recognizer.
     * The grammar tells Vosk to match ONLY these tokens, which dramatically
     * reduces false-positive rate compared to open-vocabulary recognition.
     */
    private val recognizer = Recognizer(model, 16_000f, "[\"hey airi\", \"hey airy\", \"[unk]\"]")

    private var cooldownUntilMs = 0L

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Feed a frame of audio samples to the wake-word engine.
     *
     * Call this on every [AudioRecord.read] in the capture loop.
     *
     * @param samples  PCM-16 LE audio frame from AudioRecord.
     * @param nRead    Number of valid shorts in [samples].
     * @return         `true` if the wake phrase was detected in this frame.
     */
    fun processFrame(samples: ShortArray, nRead: Int): Boolean {
        if (nRead <= 0) return false

        // Convert ShortArray → ByteArray (little-endian PCM-16)
        val bytes = ByteArray(nRead * 2)
        for (i in 0 until nRead) {
            bytes[i * 2]     = (samples[i].toInt() and 0xFF).toByte()
            bytes[i * 2 + 1] = ((samples[i].toInt() ushr 8) and 0xFF).toByte()
        }

        val accepted = recognizer.acceptWaveForm(bytes, bytes.size)
        val jsonStr  = if (accepted) recognizer.result else recognizer.partialResult

        return try {
            val obj  = JSONObject(jsonStr)
            val text = (if (accepted) obj.optString("text") else obj.optString("partial"))
                .lowercase()
                .trim()

            if (text.contains(WAKE_PHRASE)) {
                val now = System.currentTimeMillis()
                if (now < cooldownUntilMs) return false   // suppress double-fires
                cooldownUntilMs = now + COOLDOWN_MS
                Log.i(TAG, "AIRI_PROOF HOTWORD_DETECTED engine=vosk_kws text='$text'")
                recognizer.reset()
                onWakeDetected()
                true
            } else false
        } catch (_: Exception) { false }
    }

    /**
     * Reset the recognizer state — useful after a false positive or after the
     * user speaks following a wake event.
     */
    fun reset() = recognizer.reset()

    override fun close() {
        runCatching { recognizer.close() }
    }

    // ── Companion (model loading + status) ────────────────────────────────────

    companion object {

        private const val COOLDOWN_MS = 2_000L

        /**
         * Status report for the Settings UI.
         *
         * [ready] = true when a Vosk model is installed AND selected so the
         * wake-word service can start immediately.
         */
        data class Status(
            val modelInstalled: Boolean,
            val modelName:      String?,
            val activeModelId:  String?,
            val ready:          Boolean = modelInstalled
        )

        /**
         * Synchronous status snapshot — safe to call on any thread.
         * Does NOT probe disk; relies on the in-memory [VoskModelManager] state.
         */
        fun status(context: Context): Status {
            val activeId  = VoskModelManager.activeModelId.value
            val installed = VoskModelManager.installed.value
            val model     = installed.find { it.id == activeId }
            return Status(
                modelInstalled = model != null,
                modelName      = model?.displayName,
                activeModelId  = activeId,
                ready          = model != null
            )
        }

        /**
         * Attempt to load the currently-active Vosk model.
         * Returns null if no model is selected or loading fails.
         * Must be called off the main thread.
         */
        fun loadModel(context: Context): Model? {
            val dir = VoskModelManager.activeModelDir(context) ?: return null
            return runCatching { Model(dir.absolutePath) }
                .onFailure { Log.w("AIRI_VOICE", "InternalWakeWordEngine: model load failed: ${it.message}") }
                .getOrNull()
        }
    }
}
