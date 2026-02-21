package com.airi.assistant

import android.content.Context

class LlamaNative(private val context: Context) {

    companion object {
        init {
            try {
                System.loadLibrary("airi_native")
            } catch (e: UnsatisfiedLinkError) {
                android.util.Log.e("LlamaNative", "Native library airi_native not found")
            }
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
