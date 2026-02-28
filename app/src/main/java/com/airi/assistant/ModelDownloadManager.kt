package com.airi.assistant

import android.content.Context
import java.io.File

class ModelDownloadManager(private val context: Context) {
    private val modelName = "qwen2.5-1.5b-q4_k_m.gguf"

    fun getModelFile(): File {
        val dir = File(context.filesDir, "models")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, modelName)
    }

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 0
    }
}
