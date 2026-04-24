package com.airi.assistant.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.airi.assistant.ui.viewmodel.ChatMessage
import java.io.IOException

object ChatExporter {
    private const val TAG = "AIRI_STORAGE"

    fun exportToJson(context: Context, messages: List<ChatMessage>): Boolean {
        Log.e(TAG, "EXPORT_FAILED reason=SAF_CREATE_DOCUMENT_REQUIRED")
        return false
    }

    fun createExportIntent(fileName: String = buildFileName()): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun buildFileName(): String = "airi_chat_${System.currentTimeMillis()}.json"

    fun exportToUri(context: Context, uri: Uri, messages: List<ChatMessage>): Boolean {
        if (messages.isEmpty()) return false
        return try {
            val jsonContent = buildJson(messages)
            context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                stream.write(jsonContent.toByteArray(Charsets.UTF_8))
                stream.flush()
            } ?: throw IOException("Cannot open export URI for writing")
            Log.i(TAG, "EXPORT_SUCCESS uri=$uri bytes=${jsonContent.toByteArray(Charsets.UTF_8).size}")
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck("EXPORT", true, "uri=$uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "EXPORT_FAILED reason=${e.message}", e)
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck("EXPORT", false, e.message ?: "unknown")
            false
        }
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
