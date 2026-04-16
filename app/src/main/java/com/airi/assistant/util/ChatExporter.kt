package com.airi.assistant.util

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.airi.assistant.ui.viewmodel.ChatMessage
import java.io.File

object ChatExporter {

    fun exportToJson(context: Context, messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) return false
        return try {
            val fileName = "airi_chat_${System.currentTimeMillis()}.json"
            val jsonContent = buildJson(messages)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                writeViaMediaStore(context, fileName, jsonContent)
            } else {
                writeLegacy(fileName, jsonContent)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun writeViaMediaStore(context: Context, fileName: String, content: String): Boolean {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, fileName)
            put(MediaStore.Downloads.MIME_TYPE, "application/json")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/AIRI")
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return false
        resolver.openOutputStream(uri)?.use { stream ->
            stream.write(content.toByteArray(Charsets.UTF_8))
        }
        return true
    }

    private fun writeLegacy(fileName: String, content: String): Boolean {
        val dir = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            "AIRI"
        )
        dir.mkdirs()
        File(dir, fileName).writeText(content, Charsets.UTF_8)
        return true
    }

    private fun buildJson(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("[\n")
        messages.forEachIndexed { index, msg ->
            val escaped = msg.text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            sb.append("  {\n")
            sb.append("    \"role\": \"${if (msg.isUser) "user" else "assistant"}\",\n")
            sb.append("    \"content\": \"$escaped\"\n")
            sb.append("  }")
            if (index < messages.size - 1) sb.append(",")
            sb.append("\n")
        }
        sb.append("]")
        return sb.toString()
    }
}
