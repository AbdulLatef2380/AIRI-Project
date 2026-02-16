package com.airi.assistant

class LlamaNative {

    companion object {
        init {
            System.loadLibrary("airi_native")
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
