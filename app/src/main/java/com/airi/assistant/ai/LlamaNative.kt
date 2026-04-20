package com.airi.assistant.ai

import android.util.Log

object LlamaNative {
    private var available = false
    private var loadFailure: String? = null

    init {
        try {
            System.loadLibrary("airi_native")
            available = true
            Log.i("LlamaNative", "Native library airi_native loaded")
        } catch (e: UnsatisfiedLinkError) {
            available = false
            loadFailure = e.message
            Log.e("LlamaNative", "Native library airi_native not found: ${e.message}", e)
        }
    }

    fun isAvailable(): Boolean = available

    fun loadFailureMessage(): String? = loadFailure

    /**
     * واجهة لاستقبال تحديثات التقدم من محرك C++
     */
    interface ProgressCallback {
        fun onProgress(percent: Int)
    }

    /**
     * تحميل النموذج مع متابعة نسبة التقدم (حقيقي)
     */
    external fun loadModelWithProgress(
        modelPath: String,
        callback: ProgressCallback
    )

    /**
     * التحميل القديم (للتوافق أو الاختبار)
     */
    external fun loadModel(modelPath: String): String

    /**
     * توليد رد من AIRI بناءً على النص المدخل
     */
    external fun generateResponse(prompt: String): String

    /**
     * توليد الرد بشكل متدفق (Streaming)
     */
    external fun generateStream(prompt: String, onToken: (String) -> Unit)
}
