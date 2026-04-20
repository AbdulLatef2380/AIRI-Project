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

    interface ProgressCallback {
        fun onProgress(percent: Int)
    }

    external fun loadModelWithProgress(
        modelPath: String,
        callback: ProgressCallback
    )

    external fun loadModel(modelPath: String): String

    external fun generateResponse(prompt: String): String

    external fun generateStream(prompt: String, onToken: (String) -> Unit)

    external fun cancel()
}
