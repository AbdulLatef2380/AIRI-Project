package com.airi.assistant

import android.content.Context

class LlamaManager(private val context: Context) {

    private val downloader = ModelDownloadManager(context)
    private var isLoaded = false

    fun initializeModel(onReady: (Boolean) -> Unit) {

        if (!downloader.isModelDownloaded()) {
            onReady(false)
            return
        }

        val modelPath = downloader.getModelFile().absolutePath

        val result = LlamaNative.loadModel(modelPath)

        isLoaded = result == "Success"

        onReady(isLoaded)
    }

    fun generate(prompt: String): String {
        if (!isLoaded) return "Model not loaded"
        return LlamaNative.generateResponse(prompt)
    }
}
