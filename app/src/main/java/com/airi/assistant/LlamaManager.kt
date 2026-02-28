package com.airi.assistant

import android.content.Context
import kotlinx.coroutines.*
import java.io.File

class LlamaManager(private val context: Context) {
    private var isLoaded = false
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun initializeModel(onReady: (Boolean) -> Unit) {
        // نستخدم المسار القانوني وليس /sdcard/
        val modelFile = ModelDownloadManager(context).getModelFile()
        
        if (!modelFile.exists()) {
            onReady(false)
            return
        }

        scope.launch {
            val result = LlamaNative.loadModel(modelFile.absolutePath)
            isLoaded = (result == "Success")
            withContext(Dispatchers.Main) { onReady(isLoaded) }
        }
    }

    fun generate(prompt: String, onResult: (String) -> Unit) {
        if (!isLoaded) {
            onResult("المحرك غير مفعل")
            return
        }
        scope.launch {
            val response = LlamaNative.generateResponse(prompt)
            withContext(Dispatchers.Main) { onResult(response) }
        }
    }
}
