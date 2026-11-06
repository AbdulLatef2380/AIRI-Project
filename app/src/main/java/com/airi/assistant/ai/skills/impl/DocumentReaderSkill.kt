package com.airi.assistant.ai.skills.impl

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.airi.assistant.attachments.AttachmentContentExtractor
import com.airi.assistant.attachments.DocumentFormatRegistry
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DocumentReaderSkill — reads plain text and structured document files from device storage.
 *
 * Supported formats: bounded text files plus PDF/DOCX/XLSX/PPTX/ODF/EPUB text extraction.
 * Binary formats outside this list fail closed instead of being decoded as UTF-8.
 */
class DocumentReaderSkill(private val context: Context) : AiriSkill {

    override val skillId    = "document_reader"
    override val name       = "document_reader"
    override val description = "Read bounded text from common text, PDF, OOXML, ODF, and EPUB documents"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "PRODUCTIVITY"
    override val iconEmoji  = ""
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.READ_WRITE
    override val modelAccess  = SkillModelAccess.NONE

    override val requiredPermissions = listOf(
        "android.permission.READ_EXTERNAL_STORAGE"
    )

    override val parameters = mapOf(
        "uri"      to "string — content URI of the document (content://...)",
        "maxChars" to "int (optional) — max characters to extract, default 8000"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "read_document",
            description = "Read text content from a document file URI",
            parameters  = mapOf(
                "uri"      to SkillParamDef("string", "Content URI of the document to read", required = true),
                "maxChars" to SkillParamDef("int", "Max characters to return (default 8000)", required = false)
            )
        )
    )

    private val readKeywords = listOf(
        "read", "open", "show", "display", "contents of",
        "text in", "extract from", "parse", "load document"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        if (lower.contains("content://") || lower.contains("file://")) score += 50
        readKeywords.forEach { kw -> if (lower.contains(kw)) score += 8 }
        if (lower.contains("document") || lower.contains(".txt") || lower.contains(".pdf")) score += 20
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val uriString = params["uri"] as? String
            ?: return@withContext SkillResult(
                false, "",
                "No document URI provided. Share a document with AIRI to read it.",
                skillId
            )

        val maxChars = when (val value = params["maxChars"]) {
            is Int -> value
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 8000
            else -> 8000
        }.coerceIn(1, AttachmentContentExtractor.MAX_CHARS)

        return@withContext try {
            val uri = Uri.parse(uriString)
            val fileName = getFileName(uri)
            val extension = fileName?.substringAfterLast('.', "")?.lowercase() ?: ""

            val mime = context.contentResolver.getType(uri).orEmpty()
                .substringBefore(';').trim().lowercase()
            val content = if (DocumentFormatRegistry.readable(mime, fileName.orEmpty())) {
                extractStructuredDocument(uri, mime, fileName.orEmpty(), maxChars)
            } else if (isTextDocument(mime, extension)) {
                readTextFromUri(uri, maxChars)
            } else {
                throw IllegalArgumentException("Unsupported document format: ${fileName ?: "unknown"}")
            }
            if (content.isBlank()) {
                SkillResult(false, "", "Document is empty or unreadable: $fileName", skillId)
            } else {
                SkillResult(
                    success     = true,
                    data        = buildString {
                        if (fileName != null) append("Document: $fileName\n\n")
                        append(content)
                    },
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    metadata    = mapOf(
                        "file"  to (fileName ?: "unknown"),
                        "type"  to extension,
                        "chars" to "${content.length}"
                    )
                )
            }
        } catch (e: SecurityException) {
            SkillResult(false, "", "Permission denied reading document. Grant storage permission in Settings.", skillId)
        } catch (e: Exception) {
            SkillResult(false, "", "Failed to read document: ${e.message}", skillId)
        }
    }

    private fun getFileName(uri: Uri): String? {
        return runCatching {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
            }
        }.getOrNull()
    }

    private fun readTextFromUri(uri: Uri, maxChars: Int): String {
        return context.contentResolver.openInputStream(uri)
            ?.bufferedReader(Charsets.UTF_8)
            ?.use { reader ->
                val sb = StringBuilder()
                val buf = CharArray(4096)
                var total = 0
                while (total < maxChars) {
                    val read = reader.read(buf, 0, minOf(buf.size, maxChars - total))
                    if (read == -1) break
                    sb.append(buf, 0, read)
                    total += read
                }
                sb.toString()
            } ?: ""
    }

    private fun extractStructuredDocument(uri: Uri, mime: String, fileName: String, maxChars: Int): String {
        val staged = java.io.File.createTempFile("airi_reader_", ".bin", context.cacheDir)
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        total += count
                        require(total <= 25L * 1024L * 1024L) {
                            "Document exceeds the 25MB safety limit"
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: throw IllegalArgumentException("Unable to open document")
            AttachmentContentExtractor.extract(staged, mime, fileName, maxChars)
        } finally {
            staged.delete()
        }
    }

    private fun isTextDocument(mime: String, extension: String): Boolean =
        mime.startsWith("text/") || extension in setOf(
            "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm",
            "log", "yaml", "yml", "kt", "java", "py", "js", "ts", "css", "sql", "ini", "toml"
        )
}
