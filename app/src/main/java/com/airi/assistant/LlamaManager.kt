package com.airi.assistant

import android.content.Context

class LlamaManager(private val context: Context) {

    private val native = LlamaNative(context)
    private val downloader = ModelDownloadManager(context)

    fun initializeModel(onReady: () -> Unit) {

        if (!downloader.isModelDownloaded()) {
            throw IllegalStateException("Model not downloaded")
        }

        val modelPath = downloader.getModelFile().absolutePath
        native.loadModel(modelPath)
        onReady()
    }
}
