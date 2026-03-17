package com.airi.assistant.core

import android.content.Context
import android.util.Log

class VoiceManager(
    private val context: Context,
    private val listener: VoiceListener
) {

    interface VoiceListener {
        fun onWakeWordDetected()
        fun onSpeechResult(text: String)
        fun onError(error: String)
    }

    private var isListeningForWakeWord = false

    fun startWakeWordDetection() {

        if (isListeningForWakeWord) return

        Log.d("VoiceManager", "Listening for AIRI wake word")

        isListeningForWakeWord = true

        // Picovoice integration later
    }

    fun startSpeechToText() {

        Log.d("VoiceManager", "Speech to text started")

        // Vosk integration later
    }

    fun stopAll() {

        isListeningForWakeWord = false

        Log.d("VoiceManager", "Voice system stopped")
    }

    fun speak(text: String) {

        Log.i("VoiceManager", "AIRI says: $text")

        // TTS integration later
    }
}
