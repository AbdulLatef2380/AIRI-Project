package com.airi.assistant.core

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale

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
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var isListeningForWakeWord = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.getDefault())
                ttsReady = result != TextToSpeech.LANG_MISSING_DATA
                        && result != TextToSpeech.LANG_NOT_SUPPORTED
                if (!ttsReady) {
                    Log.w(TAG, "TTS language not supported — falling back to ENGLISH")
                    tts?.setLanguage(Locale.ENGLISH)
                    ttsReady = true
                }
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        Log.d(TAG, "TTS onStart utteranceId=$utteranceId")
                        listener.onSpeakingStarted()
                    }
                    override fun onDone(utteranceId: String?) {
                        Log.d(TAG, "TTS onDone utteranceId=$utteranceId")
                        listener.onSpeakingDone()
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        Log.w(TAG, "TTS utterance error: $utteranceId")
                        listener.onSpeakingDone()
                    }
                })
                Log.d(TAG, "TextToSpeech initialized successfully")
            } else {
                ttsReady = false
                Log.w(TAG, "TextToSpeech initialization failed (status=$status)")
            }
        }
    }

    fun speak(text: String) {
        if (!ttsReady || tts == null) {
            Log.w(TAG, "TTS not ready — skipping speak")
            return
        }
        val utteranceId = "airi_${System.currentTimeMillis()}"
        Log.d(TAG, "TTS speak invoked: text_len=${text.length} preview='${text.take(80)}'")
        tts!!.speak(text.trim(), TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.i(TAG, "TTS speak queued: utteranceId=$utteranceId")
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "SPEAKING")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "SPEAKING") }
    }

    fun stopSpeaking() {
        if (tts?.isSpeaking == true) {
            Log.d(TAG, "stopSpeaking: TTS was speaking, calling stop + onSpeakingDone")
            tts?.stop()
            listener.onSpeakingDone()
        } else {
            tts?.stop()
        }
        com.airi.assistant.core.analytics.ProofLogger.log("VOICE_STATE", "IDLE")
        com.airi.assistant.core.debug.RuntimeStore.update { copy(voiceState = "IDLE") }
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun startWakeWordDetection() {
        if (isListeningForWakeWord) return
        isListeningForWakeWord = true
        Log.d(TAG, "Listening for AIRI wake word")
        // Picovoice integration later
    }

    fun startSpeechToText() {
        Log.d(TAG, "Speech to text started")
        // Vosk integration later
    }

    fun stopAll() {
        isListeningForWakeWord = false
        tts?.stop()
        Log.d(TAG, "Voice system stopped")
    }

    fun destroy() {
        isListeningForWakeWord = false
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
