package com.airi.assistant.ai

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class ScannedModel(
    val path: String,
    val fileName: String,
    val sizeMb: Int
)

object ModelScout {
    private val SCAN_DIRS = listOf(
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0/AIRI"
    )

    suspend fun scan(context: Context): List<ScannedModel> = withContext(Dispatchers.IO) {
        val results = mutableListOf<ScannedModel>()
        for (dirPath in SCAN_DIRS) {
            runCatching {
                val dir = File(dirPath)
                if (!dir.exists() || !dir.canRead()) return@runCatching
                val ggufFiles = dir.listFiles { f -> f.isFile && f.name.endsWith(".gguf") }
                    ?: return@runCatching
                for (file in ggufFiles) {
                    val validation = ModelValidator.validate(file, context, 0)
                    if (validation is ValidationResult.Valid) {
                        results.add(
                            ScannedModel(
                                path = file.absolutePath,
                                fileName = file.name,
                                sizeMb = (file.length() / (1024L * 1024L)).toInt()
                            )
                        )
                    }
                }
            }
        }
        results
    }
}
