package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.*

class LlamaManager(private val context: Context) {

    private val downloader = ModelDownloadManager(context)
    private var isLoaded = false

    private val scope = CoroutineScope(Dispatchers.IO)

    fun initializeModel(onReady: (Boolean) -> Unit) {

        if (!downloader.isModelDownloaded()) {
            onReady(false)
            return
        }

        scope.launch {

            val modelPath = downloader.getModelFile().absolutePath
            val result = LlamaNative.loadModel(modelPath)

            isLoaded = result == "Success"

            withContext(Dispatchers.Main) {
                onReady(isLoaded)
            }
        }
    }

    fun generate(prompt: String, onResult: (String) -> Unit) {

        if (!isLoaded) {
            onResult("Model not loaded")
            return
        }

        scope.launch {

            val response = LlamaNative.generateResponse(prompt)

            withContext(Dispatchers.Main) {
                onResult(response)
            }
        }
    }
}
