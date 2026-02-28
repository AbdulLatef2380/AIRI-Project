package com.airi.assistant

import android.content.Context
import java.io.File

class ModelDownloadManager(private val context: Context) {

    private val modelName = "qwen2.5-1.5b-q4_k_m.gguf"

    private fun getModelsDir(): File {
        val baseDir = context.getExternalFilesDir(null)
            ?: throw IllegalStateException("External files dir not available")

        val modelsDir = File(baseDir, "models")
        if (!modelsDir.exists()) modelsDir.mkdirs()

        return modelsDir
    }

    fun getModelFile(): File {
        return File(getModelsDir(), modelName)
    }

    fun isModelDownloaded(): Boolean {
        val file = getModelFile()

        // النموذج حجمه يقارب ~900MB
        // نتحقق من حجم منطقي (>100MB مثلاً) لتجنب اعتبار ملف جزئي صحيحًا
        return file.exists() && file.length() > 100L * 1024 * 1024
    }
}
