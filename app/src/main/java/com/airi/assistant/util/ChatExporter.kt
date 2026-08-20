package com.airi.assistant.util

import android.content.Context
import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import com.airi.assistant.domain.verification.VerificationTracker
import com.airi.assistant.ui.viewmodel.ChatMessage

object ChatExporter {
    private const val TAG = "AIRI_STORAGE"
    private const val PDF_PAGE_WIDTH = 595
    private const val PDF_PAGE_HEIGHT = 842
    private const val PDF_MARGIN = 42
    private const val PDF_TEXT_SIZE = 11f
    private const val PDF_CHUNK_LENGTH = 2_400

    fun exportToJson(context: Context, messages: List<ChatMessage>): Boolean {
        Log.w(TAG, "EXPORT_REQUIRES_DESTINATION")
        return false
    }

    fun createExportIntent(mimeType: String, fileName: String): Intent =
        Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = mimeType
            putExtra(Intent.EXTRA_TITLE, fileName)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    fun buildFileName(extension: String): String = "airi_chat_${System.currentTimeMillis()}.$extension"

    fun exportToUri(
        context: Context,
        uri: Uri,
        messages: List<ChatMessage>,
        mimeType: String
    ): Boolean {
        if (messages.isEmpty()) return false
        return try {
            when (mimeType) {
                "application/pdf" -> writePdf(context, uri, buildMarkdown(messages))
                else -> {
                    val content = when (mimeType) {
                        "application/json" -> buildJson(messages)
                        else -> buildMarkdown(messages)
                    }
                    context.contentResolver.openOutputStream(uri, "wt")?.use { stream ->
                        stream.write(content.toByteArray(Charsets.UTF_8))
                        stream.flush()
                    } ?: return false
                }
            }
            Log.i(TAG, "EXPORT_SUCCESS mime=$mimeType")
            VerificationTracker.recordCheck("EXPORT", true, "mime=$mimeType")
            true
        } catch (error: Exception) {
            val type = error.javaClass.simpleName
            Log.e(TAG, "EXPORT_FAILED type=$type", error)
            VerificationTracker.recordCheck("EXPORT", false, "type=$type")
            false
        }
    }

    private fun writePdf(context: Context, uri: Uri, content: String) {
        val document = PdfDocument()
        try {
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                textSize = PDF_TEXT_SIZE * context.resources.displayMetrics.scaledDensity
                color = android.graphics.Color.BLACK
            }
            val pageWidth = PDF_PAGE_WIDTH - (PDF_MARGIN * 2)
            val chunks = content.chunked(PDF_CHUNK_LENGTH).flatMap(::splitAtLineBoundary)
            chunks.forEachIndexed { index, chunk ->
                val page = document.startPage(
                    PdfDocument.PageInfo.Builder(PDF_PAGE_WIDTH, PDF_PAGE_HEIGHT, index + 1).create()
                )
                val layout = StaticLayout.Builder
                    .obtain(chunk, 0, chunk.length, paint, pageWidth)
                    .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                    .setIncludePad(false)
                    .setLineSpacing(2f, 1f)
                    .build()
                page.canvas.save()
                page.canvas.translate(PDF_MARGIN.toFloat(), PDF_MARGIN.toFloat())
                layout.draw(page.canvas)
                page.canvas.restore()
                document.finishPage(page)
            }
            context.contentResolver.openOutputStream(uri, "wt")?.use(document::writeTo)
                ?: error("Output stream unavailable")
        } finally {
            document.close()
        }
    }

    private fun splitAtLineBoundary(chunk: String): List<String> {
        if (chunk.length < PDF_CHUNK_LENGTH) return listOf(chunk)
        val boundary = chunk.lastIndexOf('\n').takeIf { it > PDF_CHUNK_LENGTH / 2 }
            ?: chunk.lastIndexOf(' ').takeIf { it > PDF_CHUNK_LENGTH / 2 }
            ?: return listOf(chunk)
        return listOf(chunk.substring(0, boundary), chunk.substring(boundary).trimStart())
            .filter { it.isNotBlank() }
    }

    private fun buildMarkdown(messages: List<ChatMessage>): String = buildString {
        append("# AIRI Chat Export\n\n")
        messages.forEach { message ->
            val role = if (message.isUser) "**User**" else "**AIRI**"
            append(role).append(":\n")
            append(message.text).append("\n\n---\n\n")
        }
    }

    private fun buildJson(messages: List<ChatMessage>): String = buildString {
        append("[\n")
        messages.forEachIndexed { index, message ->
            val escaped = message.text
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
            append("  {\n")
            append("    \"role\": \"")
            append(if (message.isUser) "user" else "assistant")
            append("\",\n    \"content\": \"")
            append(escaped).append("\"\n  }")
            if (index < messages.lastIndex) append(',')
            append('\n')
        }
        append(']')
    }
}
