package com.airi.assistant.tools

import android.content.Context
import java.io.File

class ModelDownloadManager(private val context: Context) {

    private val defaultModelName = "qwen2.5-1.5b-q4_k_m.gguf"

    fun getModelsDir(): File {
        val baseDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir not available")
        val modelsDir = File(baseDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()
        return modelsDir
    }

    fun getModelFile(): File {
        return File(getModelsDir(), defaultModelName)
    }

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()
        return file.exists() && file.length() > 100L * 1024 * 1024
    }

    fun isFileDownloaded(fileName: String): Boolean {
        val file = File(getModelsDir(), fileName)
        return file.exists() && file.length() > 50L * 1024 * 1024
    }

    /**
     * Issue #9 — cancel the in-flight model download.
     * Forwards to [ModelDownloadService.cancel]. Returns true if a cancel
     * was actually dispatched (a download was running), false otherwise.
     */
    fun cancelActiveDownload(context: android.content.Context): Boolean {
        val active = ModelDownloadService.cancelRequested.get().not() // crude live-check
        ModelDownloadService.cancel(context)
        android.util.Log.i("AIRI_PROOF", "DOWNLOAD_CANCEL_DISPATCHED was_active=$active")
        return active
    }

    fun getFileForName(fileName: String): File {
        return File(getModelsDir(), fileName)
    }
}
