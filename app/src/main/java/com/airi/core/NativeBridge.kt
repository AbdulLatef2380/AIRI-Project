package com.airi.core

import android.util.Log

object NativeBridge {
    private const val TAG = "AIRI_BRIDGE"

    init {
        try {
            System.loadLibrary("airi_native")
            Log.i(TAG, "Native library loaded successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load native library: ${e.message}")
        }
    }

    /**
     * تهيئة المحرك الأساسي (llama_backend_init)
     */
    external fun initEngine(): Int

    /**
     * تحميل نموذج GGUF من المسار المحدد
     */
    external fun loadModel(modelPath: String): Int

    /**
     * تحرير النموذج والسياق من الذاكرة
     */
    external fun freeModel()

    /**
     * إغلاق المحرك بالكامل (llama_backend_deinit)
     */
    external fun deinitEngine()

    /**
     * طلب توليد رد من المحرك (Inference)
     */
    external fun generateResponse(prompt: String): String
}
