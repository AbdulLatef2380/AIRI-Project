package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import com.airi.assistant.connector.local.VoiceConnector
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Implements [VoiceConnector.VoiceBackend] using the on-device Vosk STT engine.
 *
 * B-08 FIX: Previously VoiceConnector had `backend = null` — always disconnected.
 * This class is the concrete VoiceBackend registered by ConnectorBootstrap.
 *
 * Audio format expected by [transcribe]: raw PCM-16 mono 16 kHz little-endian bytes.
 */
class VoskVoiceBackend(private val context: Context) : VoiceConnector.VoiceBackend {

    private val TAG = "AIRI_VOICE_BACKEND"

    // Lazily-loaded Vosk model — released in [release()].
    @Volatile private var loadedModel: Model? = null

    override suspend fun warmUp(): Boolean = withContext(Dispatchers.IO) {
        if (!VoskModelManager.isReady(context)) {
            Log.i(TAG, "warmUp skipped — no active Vosk model")
            return@withContext false
        }
        if (loadedModel == null) {
            val dir = VoskModelManager.activeModelDir(context)
            if (dir == null || !dir.exists()) {
                Log.w(TAG, "warmUp failed — model dir not found: $dir")
                return@withContext false
            }
            loadedModel = runCatching { Model(dir.absolutePath) }
                .onFailure { Log.e(TAG, "Model() init failed: ${it.message}", it) }
                .getOrNull()
        }
        val ready = loadedModel != null
        Log.i(TAG, "warmUp ready=$ready")
        ready
    }

    /**
     * Transcribe raw PCM-16 mono 16 kHz [audio] bytes.
     * Creates a one-shot [Recognizer] per call to avoid shared state.
     */
    override suspend fun transcribe(audio: ByteArray): String = withContext(Dispatchers.IO) {
        val model = loadedModel
            ?: return@withContext "[voice: no model — call warmUp first]"
        try {
            val recognizer = Recognizer(model, 16_000f)
            val shorts = ShortArray(audio.size / 2) { i ->
                ((audio[i * 2 + 1].toInt() shl 8) or (audio[i * 2].toInt() and 0xFF)).toShort()
            }
            recognizer.acceptWaveForm(shorts, shorts.size)
            val json = recognizer.finalResult  // {"text": "hello world"}
            recognizer.close()
            // Parse "text" field from Vosk JSON without extra dependency
            Regex(""""text"\s*:\s*"([^"]*)"""")
                .find(json)?.groupValues?.getOrElse(1) { "" }?.trim() ?: ""
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed: ${t.message}", t)
            "[voice: transcription error]"
        }
    }

    override suspend fun release(): Unit = withContext(Dispatchers.IO) {
        runCatching { loadedModel?.close() }
        loadedModel = null
        Log.i(TAG, "released")
        Unit
    }
}
