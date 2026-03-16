package com.airi.assistant.tools

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object FileUtils {
    fun copyToInternalStorage(context: Context, uri: Uri): String {
        val fileName = "imported_model_${System.currentTimeMillis()}.gguf"
        val destFile = File(context.filesDir, fileName)
        
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(destFile).use { output ->
                input.copyTo(output)
            }
        }
        return destFile.absolutePath
    }
}
