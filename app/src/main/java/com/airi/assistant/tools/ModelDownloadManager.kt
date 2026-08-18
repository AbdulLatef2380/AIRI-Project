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
     * Forwards to [ModelDownloadService.cancel]. Returns true iff a download
     * was actually in progress at the time of the call.
     *
     * The previous implementation inferred "active" from the cancel flag
     * being FALSE, which gave a false positive after any prior completed
     * download (cancelRequested is reset to false at the start of every
     * download). We now read the explicit isDownloading AtomicBoolean that
     * the service sets in onStartCommand and clears in the worker's finally
     * block — so this answer is authoritative.
     */
    fun cancelActiveDownload(context: android.content.Context): Boolean {
        val active = ModelDownloadService.isDownloading.get()
        ModelDownloadService.cancel(context)
        android.util.Log.i("AIRI", "DOWNLOAD_CANCEL_DISPATCHED was_active=$active")
        return active
    }

    /** True iff a download is currently in progress. */
    fun isDownloadInProgress(): Boolean = ModelDownloadService.isDownloading.get()

    fun getFileForName(fileName: String): File {
        return File(getModelsDir(), fileName)
    }
}
