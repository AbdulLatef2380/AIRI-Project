package com.airi.assistant

import android.content.Context
import android.util.Log

/**
 * مدير الصوت لـ AIRI.
 * يتعامل مع اكتشاف كلمة التنبيه (Wake Word) وتحويل الكلام إلى نص (STT).
 */
class VoiceManager(private val context: Context, private val listener: VoiceListener) {

    interface VoiceListener {
        fun onWakeWordDetected()
        fun onSpeechResult(text: String)
        fun onError(error: String)
    }

    private var isListeningForWakeWord = false

    /**
     * بدء الاستماع لكلمة التنبيه "AIRI" (باستخدام Porcupine/Picovoice)
     */
    fun startWakeWordDetection() {
        if (isListeningForWakeWord) return
        
        Log.d("VoiceManager", "بدء الاستماع لنداء AIRI...")
        isListeningForWakeWord = true
        
        // هنا سيتم دمج مكتبة Picovoice
        // عند الاكتشاف: listener.onWakeWordDetected()
    }

    /**
     * بدء تحويل الكلام إلى نص (باستخدام Vosk)
     * يتم استدعاؤه فقط بعد اكتشاف Wake Word
     */
    fun startSpeechToText() {
        Log.d("VoiceManager", "بدء تحويل الكلام إلى نص...")
        
        // هنا سيتم دمج مكتبة Vosk
        // عند الحصول على نتيجة: listener.onSpeechResult(text)
    }

    fun stopAll() {
        isListeningForWakeWord = false
        // إيقاف المحركات وتحرير الموارد
    }

    /**
     * تحويل النص إلى كلام (TTS)
     */
    fun speak(text: String) {
        Log.i("VoiceManager", "AIRI تقول: $text")
        // هنا سيتم دمج محرك TTS (مثل Google TTS أو محرك محلي)
    }
}
