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
     * تحميل نموذج الذكاء الاصطناعي (GGUF)
     */
    external fun loadModel(modelPath: String): String

    /**
     * توليد رد من AIRI بناءً على النص المدخل
     */
    external fun generateResponse(prompt: String): String
}
