package com.airi.assistant.core

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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

    // PHASE 4 (audio polish): STT scope runs on IO so the Vosk model load
    // (~30-90 MB unzip + native init) and the audio-capture loop never
    // block the UI thread. Listener callbacks that touch UI marshal back
    // through `mainHandler` themselves; nothing here needs Main.
    private val sttScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    // ── PHASE 2: Streaming TTS ───────────────────────────────────────────────
    //
    // The chat path streams tokens into `_streamingText`. Until now the user
    // had to wait for the complete reply before TTS started. With streaming
    // TTS we accumulate tokens here, flush at sentence boundaries
    // (.!?؟،\n) so the engine speaks naturally, and use QUEUE_ADD so chunks
    // play back-to-back without re-synthesizing.
    //
    // Lifecycle:
    //   ttsStreamReset()   — call when a fresh assistant turn starts
    //   ttsStreamAppend(s) — call with each streamed delta
    //   ttsStreamFlush()   — call when the assistant turn completes
    private val ttsStreamBuffer = StringBuilder()
    @Volatile private var ttsStreamActive = false

    fun ttsStreamReset() {
        ttsStreamBuffer.setLength(0)
        ttsStreamActive = true
        Log.i("AIRI_PROOF", "TTS_STREAM_RESET")
    }

    fun ttsStreamAppend(delta: String) {
        if (!ttsReady || tts == null || !ttsStreamActive) return
        ttsStreamBuffer.append(delta)
        // Flush any complete sentence(s) currently in the buffer.
        var flushed = 0
        while (true) {
            val s = ttsStreamBuffer
            // Find the earliest sentence terminator. Includes Arabic
            // question mark (؟) and Arabic comma (،) so RTL replies feel
            // natural — Arabic full-stop is the same '.' as Latin.
            var idx = -1
            for (i in 0 until s.length) {
                val c = s[i]
                if (c == '.' || c == '!' || c == '?' || c == '؟' ||
                    c == '،' || c == '\n') { idx = i; break }
            }
            if (idx < 0) break
            val sentence = s.substring(0, idx + 1).trim()
            s.delete(0, idx + 1)
            if (sentence.isNotEmpty()) {
                val utteranceId = "airi_stream_${System.currentTimeMillis()}_$flushed"
                tts!!.speak(sentence, TextToSpeech.QUEUE_ADD, null, utteranceId)
                flushed++
            }
        }
        if (flushed > 0) {
            com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "SPEAKING")
            com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "SPEAKING") }
            Log.i("AIRI_PROOF", "TTS_STREAM_FLUSH chunks=$flushed remaining_buf=${ttsStreamBuffer.length}")
        }
    }

    /** Flush any tail not terminated by punctuation. Safe to call multiple times. */
    fun ttsStreamFlush() {
        if (!ttsReady || tts == null || !ttsStreamActive) return
        val tail = ttsStreamBuffer.toString().trim()
        ttsStreamBuffer.setLength(0)
        ttsStreamActive = false
        if (tail.isNotEmpty()) {
            val utteranceId = "airi_stream_tail_${System.currentTimeMillis()}"
            tts!!.speak(tail, TextToSpeech.QUEUE_ADD, null, utteranceId)
            Log.i("AIRI_PROOF", "TTS_STREAM_TAIL chars=${tail.length}")
        }
        Log.i("AIRI_PROOF", "TTS_STREAM_DONE")
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

    /**
     * True iff *some* speech-recognition path is available — either:
     *   1. A Vosk model is installed (preferred, fully offline), or
     *   2. The platform [SpeechRecognizer] reports it is available
     *      (Android built-in / Google offline / OEM engine).
     *
     * The UI only needs to know "can we listen at all"; pick of the
     * concrete engine happens inside [startSpeechToText].
     */
    fun isSpeechRecognitionAvailable(): Boolean =
        VoskModelManager.isReady(context.applicationContext) ||
        SpeechRecognizer.isRecognitionAvailable(context.applicationContext)

    /** True iff the advanced (Vosk) path is wired. */
    fun isVoskAvailable(): Boolean =
        VoskModelManager.isReady(context.applicationContext)

    fun startSpeechToText() {
        if (sttActive) {
            Log.d(TAG, "STT already active — ignoring duplicate start")
            return
        }
        // PHASE 2 fix: Android SpeechRecognizer is now the PREFERRED path.
        //   • Zero-download — works on every Pixel/Samsung/Xiaomi out of box.
        //   • Honors EXTRA_PREFER_OFFLINE on devices with offline assets.
        //   • Vosk remains the fully-offline fallback (no deletion).
        // Fallback ladder: Android STT → Vosk → error.
        val androidAvailable =
            SpeechRecognizer.isRecognitionAvailable(context.applicationContext)
        Log.i("AIRI_PROOF",
            "STT_AVAILABILITY android=$androidAvailable vosk=${isVoskAvailable()}")
        if (androidAvailable) {
            Log.i("AIRI_PROOF", "STT_ENGINE_PICKED engine=android_native")
            startPlatformSpeechToText()
            return
        }
        if (!isVoskAvailable()) {
            Log.i("AIRI_PROOF", "STT_ENGINE_PICKED engine=none reason=both_unavailable")
            // Distinct code so UI can route the user to install Vosk OR
            // enable Google's speech service (whichever they have access to).
            listener.onError("stt_unavailable")
            return
        }
        Log.i("AIRI_PROOF", "STT_ENGINE_PICKED engine=vosk reason=android_unavailable")
        sttActive = true
        listener.onListeningStarted()
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "LISTENING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "LISTENING") }

        sttJob = sttScope.launch {
            // PHASE 4: model load now happens off the main thread (sttScope = IO).
            val model = sttModel ?: VoskModelManager.loadActiveModel(context.applicationContext)
            if (model == null) {
                sttActive = false
                postToMain { listener.onListeningStopped() }
                postToMain { listener.onError("vosk_model_load_failed") }
                return@launch
            }
            sttModel = model
            val engine = VoskEngine(context.applicationContext, model)
            sttEngine = engine
            engine.start(
                scope     = sttScope,
                // PHASE 4: marshal every Vosk callback to the main thread.
                // Without this the listener (Compose state writes, snackbar
                // posts, etc.) would mutate UI state from the audio worker.
                onPartial = { partial -> postToMain { listener.onPartialResult(partial) } },
                onFinal   = { text ->
                    sttActive = false
                    sttEngine?.release()
                    sttEngine = null
                    postToMain {
                        listener.onListeningStopped()
                        if (text.isNotBlank()) listener.onSpeechResult(text)
                        else listener.onError("stt_empty_result")
                    }
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                },
                onError   = { err ->
                    sttActive = false
                    sttEngine?.release()
                    sttEngine = null
                    postToMain {
                        listener.onListeningStopped()
                        listener.onError(err)
                    }
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
            )
        }
    }

    /** PHASE 4 helper: run [block] on the main thread (no-op detour if
     *  we're already there) so callers don't pay for a Handler post when
     *  the caller is already UI-bound. */
    private inline fun postToMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            block()
        } else {
            mainHandler.post { block() }
        }
    }

    fun stopSpeechToText() {
        // Engine flushes a final result on stop()
        sttEngine?.stop()
        platformRecognizer?.let {
            try { it.stopListening() } catch (_: Throwable) {}
        }
    }

    // ── Platform SpeechRecognizer fallback (no model download) ───────────────
    //
    // Used when no Vosk model is installed. Backed by Android's built-in
    // recognizer (Google offline voice typing, Samsung Bixby STT, or OEM
    // service). May require network on devices without offline assets.
    @Volatile private var platformRecognizer: SpeechRecognizer? = null

    private fun startPlatformSpeechToText() {
        sttActive = true
        listener.onListeningStarted()
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "LISTENING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "LISTENING") }

        mainHandler.post {
            val recognizer = try {
                SpeechRecognizer.createSpeechRecognizer(context.applicationContext)
            } catch (e: Throwable) {
                Log.w(TAG, "Platform SpeechRecognizer.create failed: ${e.message}")
                sttActive = false
                listener.onListeningStopped()
                listener.onError("stt_unavailable")
                return@post
            }
            platformRecognizer = recognizer
            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onPartialResults(partialResults: Bundle?) {
                    val text = partialResults
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    if (text.isNotBlank()) listener.onPartialResult(text)
                }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull().orEmpty()
                    sttActive = false
                    try { recognizer.destroy() } catch (_: Throwable) {}
                    if (platformRecognizer === recognizer) platformRecognizer = null
                    listener.onListeningStopped()
                    if (text.isNotBlank()) listener.onSpeechResult(text)
                    else listener.onError("stt_empty_result")
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
                override fun onError(error: Int) {
                    sttActive = false
                    try { recognizer.destroy() } catch (_: Throwable) {}
                    if (platformRecognizer === recognizer) platformRecognizer = null
                    listener.onListeningStopped()
                    listener.onError("stt_platform_error_$error")
                    com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
                }
            })
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                         RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
                // Prefer offline if the engine supports it (no-op on engines that don't).
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            }
            try {
                recognizer.startListening(intent)
            } catch (e: Throwable) {
                Log.w(TAG, "Platform recognizer startListening failed: ${e.message}")
                sttActive = false
                try { recognizer.destroy() } catch (_: Throwable) {}
                platformRecognizer = null
                listener.onListeningStopped()
                listener.onError("stt_unavailable")
            }
        }
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
        platformRecognizer?.let { r -> try { r.destroy() } catch (_: Throwable) {} }
        platformRecognizer = null
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
