package com.airi.assistant.tools

import android.content.Intent
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

data class ModelImportCopyResult(
    val file: File,
    val sourceSizeBytes: Long,
    val copiedBytes: Long
)

object FileUtils {
    private const val TAG = "AIRI_STORAGE"
    private const val MIN_GGUF_HEADER_BYTES = 8L

    fun copyToInternalStorage(context: Context, uri: Uri): String {
        return copyModelFromSaf(context, uri).file.absolutePath
    }

    fun copyModelFromSaf(context: Context, uri: Uri): ModelImportCopyResult {
        persistReadPermission(context, uri)
        val resolver = context.contentResolver
        val originalName = queryDisplayName(context, uri)
            ?.takeIf { it.endsWith(".gguf", ignoreCase = true) }
            ?: "imported_model_${System.currentTimeMillis()}.gguf"
        val safeName = originalName.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val modelsDir = File(context.filesDir, "models").apply {
            if (!exists() && !mkdirs()) throw IOException("Cannot create internal models directory")
        }
        // Never truncate an existing destination in place. The active llama.cpp
        // model is mmap'ed, and FileOutputStream(dest, false) would invalidate
        // that mapping while the native context can still read it, producing a
        // process-level SIGBUS instead of a catchable Kotlin exception. Use a
        // fresh filename for every import and publish it only after the copy is
        // complete.
        val base = File(modelsDir, safeName)
        val destFile = if (!base.exists()) base else {
            val stem = base.nameWithoutExtension
            val ext = base.extension.takeIf { it.isNotBlank() }?.let { ".${it}" }.orEmpty()
            File(modelsDir, "${stem}_${System.currentTimeMillis()}$ext")
        }
        val tempFile = File(modelsDir, ".${destFile.name}.part")
        val expectedSize = querySize(context, uri)
        var copiedBytes = 0L
        try {
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(tempFile, false).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        output.write(buffer, 0, read)
                        copiedBytes += read
                    }
                    output.fd.sync()
                }
            } ?: throw IOException("Cannot open selected model URI for reading")

            if (expectedSize > 0 && copiedBytes != expectedSize) {
                throw IOException("Model copy incomplete expected=$expectedSize copied=$copiedBytes")
            }
            if (!tempFile.exists() || tempFile.length() < MIN_GGUF_HEADER_BYTES) {
                throw IOException("Model file invalid or incomplete size=${tempFile.length()}")
            }
            if (!tempFile.renameTo(destFile)) {
                throw IOException("Cannot publish imported model atomically")
            }
        } finally {
            tempFile.delete()
        }
        Log.i(TAG, "IMPORT_COPY_SUCCESS uri=$uri dest=${destFile.absolutePath} expected=$expectedSize copied=$copiedBytes")
        return ModelImportCopyResult(destFile, expectedSize, copiedBytes)
    }

    private fun persistReadPermission(context: Context, uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION
            )
        }.onFailure { e ->
            Log.w(TAG, "Persistable read permission not retained uri=$uri reason=${e.message}")
        }
    }

    private fun queryDisplayName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun querySize(context: Context, uri: Uri): Long {
        return context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getLong(0) else -1L
        } ?: -1L
    }
}
