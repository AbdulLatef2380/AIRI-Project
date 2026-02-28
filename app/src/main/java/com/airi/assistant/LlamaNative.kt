package com.airi.assistant

import android.util.Log

object LlamaNative {

    init {
        try {
            System.loadLibrary("airi_native")
        } catch (e: UnsatisfiedLinkError) {
            Log.e("LlamaNative", "Native library airi_native not found: ${e.message}")
        }
    }

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
}
