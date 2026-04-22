package com.airi.assistant.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import com.airi.assistant.voice.HotwordService
import com.airi.assistant.voice.VoskEngine
import com.airi.assistant.voice.VoskModelManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.vosk.Model
import java.util.Locale

/**
 * Owns three voice subsystems:
 *
 *   1. TextToSpeech — uses the platform engine (no network).
 *
 *   2. Wake-word ("Hey AIRI") — delegated to [HotwordService], which runs
 *      Picovoice Porcupine on a foreground microphone. When either the
 *      AccessKey or the bundled .ppn keyword file is missing the service
 *      refuses to start and the Voice Settings UI explains why.
 *
 *   3. Speech-to-text — driven by [VoskEngine] using a model installed via
 *      [VoskModelManager]. NO RecognizerIntent, NO SpeechRecognizer, NO
 *      Google Voice Search. When no model is installed, [startSpeechToText]
 *      surfaces a clear error so the UI can route the user to the model
 *      downloader.
 */
class VoiceManager(
    private val context: Context,
    private val listener: VoiceListener
) {

    interface VoiceListener {
        fun onWakeWordDetected()
        fun onSpeechResult(text: String)
        fun onError(error: String)
        fun onSpeakingStarted() = Unit
        fun onSpeakingDone()    = Unit
        fun onListeningStarted() = Unit
        fun onListeningStopped() = Unit
        fun onPartialResult(text: String) = Unit
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isListeningForWakeWord = false
    private val mainHandler = Handler(Looper.getMainLooper())

    private val sttScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    @Volatile private var sttEngine: VoskEngine? = null
    @Volatile private var sttModel: Model? = null
    @Volatile private var sttJob: Job? = null
    @Volatile private var sttActive = false

    init {
        VoskModelManager.init(context.applicationContext)
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA &&
                           result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    Log.w(TAG, "TTS language not supported — falling back to ENGLISH")
                    tts?.setLanguage(Locale.ENGLISH)
                    ttsReady = true
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) { listener.onSpeakingStarted() }
                    override fun onDone(utteranceId: String?)  { listener.onSpeakingDone() }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { listener.onSpeakingDone() }
                })
                Log.d(TAG, "TextToSpeech initialized successfully")
            } else {
                ttsReady = false
                Log.w(TAG, "TextToSpeech initialization failed (status=$status)")
            }
        }
    }

    // ── TTS ──────────────────────────────────────────────────────────────────

    fun speak(text: String) {
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready — skipping speak")
            return
        }
        val utteranceId = "airi_${System.currentTimeMillis()}"
        tts!!.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "SPEAKING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "SPEAKING") }
    }

    fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            tts?.stop()
            listener.onSpeakingDone()
        } else {
            tts?.stop()
        }
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "IDLE")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    // ── Wake word (Porcupine via HotwordService) ─────────────────────────────

    fun startWakeWordDetection() {
        if (isListeningForWakeWord) return
        isListeningForWakeWord = true
        Log.d(TAG, "Starting on-device hotword service ('Hey AIRI')")
        HotwordService.start(context.applicationContext)
    }

    fun stopWakeWordDetection() {
        if (!isListeningForWakeWord) return
        isListeningForWakeWord = false
        Log.d(TAG, "Stopping on-device hotword service")
        HotwordService.stop(context.applicationContext)
    }

    // ── Speech-to-text (Vosk only) ───────────────────────────────────────────

    /** True iff a Vosk model is installed and selected. */
    fun isSpeechRecognitionAvailable(): Boolean =
        VoskModelManager.isReady(context.applicationContext)

    fun startSpeechToText() {
        if (sttActive) {
            Log.d(TAG, "STT already active — ignoring duplicate start")
            return
        }
        if (!isSpeechRecognitionAvailable()) {
            listener.onError("vosk_model_missing")
            return
        }
        sttActive = true
        listener.onListeningStarted()
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "LISTENING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "LISTENING") }

        sttJob = sttScope.launch {
            val model = sttModel ?: VoskModelManager.loadActiveModel(context.applicationContext)
            if (model == null) {
                sttActive = false
                listener.onListeningStopped()
                listener.onError("vosk_model_load_failed")
                return@launch
            }
            sttModel = model
            val engine = VoskEngine(context.applicationContext, model)
            sttEngine = engine
            engine.start(
                scope     = sttScope,
                onPartial = { partial -> listener.onPartialResult(partial) },
                onFinal   = { text ->
                    sttActive = false
                    sttEngine?.release()
                    sttEngine = null
                    listener.onListeningStopped()
                    if (text.isNotBlank()) listener.onSpeechResult(text)
                    else listener.onError("stt_empty_result")
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                },
                onError   = { err ->
                    sttActive = false
                    sttEngine?.release()
                    sttEngine = null
                    listener.onListeningStopped()
                    listener.onError(err)
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
            )
        }
    }

    fun stopSpeechToText() {
        // Engine flushes a final result on stop()
        sttEngine?.stop()
    }

    fun stopAll() {
        stopWakeWordDetection()
        stopSpeechToText()
        tts?.stop()
        Log.d(TAG, "Voice system stopped")
    }

    fun destroy() {
        isListeningForWakeWord = false
        sttJob?.cancel()
        sttEngine?.release()
        sttEngine = null
        try { sttModel?.close() } catch (_: Throwable) {}
        sttModel = null
        sttScope.cancel()
        tts?.stop()
        tts?.shutdown()
        tts = null
        ttsReady = false
        Log.d(TAG, "VoiceManager destroyed")
    }

    private companion object {
        private const val TAG = "AIRI_VOICE"
    }
}
