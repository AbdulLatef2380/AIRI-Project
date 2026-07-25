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

    fun createExportIntent(mimeType: String, fileName: String): Intent {
        return Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    fun buildFileName(extension: String): String = "airi_chat_${System.currentTimeMillis()}.$extension"

    fun exportToUri(context: Context, uri: Uri, messages: List<ChatMessage>, mimeType: String): Boolean {
        if (messages.isEmpty()) return false
        return try {
            val content = when (mimeType) {
                "application/json" -> buildJson(messages)
                "text/markdown"   -> buildMarkdown(messages)
                "application/pdf" -> buildMarkdown(messages) // PDF uses MD as source for now, or handled differently
                else -> buildMarkdown(messages)
            }
            
            if (mimeType == "application/pdf") {
                // PDF generation requires a library like iText or similar.
                // For now, we'll write the markdown text as a placeholder or use a basic PDF writer if available.
                // In a real app, we'd use a PDF library.
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            } else {
                context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                    stream.write(content.toByteArray(Charsets.UTF_8))
                    stream.flush()
                }
            }
            
            Log.i(TAG, "EXPORT_SUCCESS uri=$uri mime=$mimeType")
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck("EXPORT", true, "uri=$uri")
            true
        } catch (e: Exception) {
            Log.e(TAG, "EXPORT_FAILED reason=${e.message}", e)
            com.airi.assistant.domain.verification.VerificationTracker.recordCheck("EXPORT", false, e.message ?: "unknown")
            false
        }
    }

    private fun buildMarkdown(messages: List<ChatMessage>): String {
        val sb = StringBuilder()
        sb.append("# AIRI Chat Export\n\n")
        messages.forEach { msg ->
            val role = if (msg.isUser) "**User**" else "**AIRI**"
            sb.append("$role:\n${msg.text}\n\n---\n\n")
        }
        return sb.toString()
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
