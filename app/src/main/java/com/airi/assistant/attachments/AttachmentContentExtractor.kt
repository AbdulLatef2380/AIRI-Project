package com.airi.assistant.attachments

import com.airi.assistant.ai.skills.impl.PdfLiteralTextExtractor
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts user-selected document content locally before it is sent to a model.
 * Extraction is bounded, treats attachment content as untrusted data, and never
 * executes embedded macros, scripts, links, or external XML entities.
 */
object AttachmentContentExtractor {
    const val MAX_CHARS = 512_000
    private const val MAX_ZIP_ENTRIES = 2_000
    private const val MAX_ZIP_ENTRY_BYTES = 8L * 1024L * 1024L

    fun supports(mimeType: String, fileName: String): Boolean =
        DocumentFormatRegistry.readable(mimeType, fileName)

    fun extract(file: File, mimeType: String, fileName: String, maxChars: Int = MAX_CHARS): String {
        require(maxChars > 0)
        require(file.isFile && file.length() > 0L) { "Attachment is empty or missing" }
        val format = DocumentFormatRegistry.find(mimeType, fileName)
            ?: throw IllegalArgumentException("Unsupported document format: ${fileName.ifBlank { "unknown" }}")
        val text = when (format.family) {
            DocumentFormatRegistry.Family.PDF -> PdfLiteralTextExtractor.extract(file, maxChars)
            DocumentFormatRegistry.Family.TEXT -> readText(file, maxChars)
            DocumentFormatRegistry.Family.OOXML,
            DocumentFormatRegistry.Family.ODF,
            DocumentFormatRegistry.Family.EPUB -> extractZipXml(file, format.family, maxChars)
            DocumentFormatRegistry.Family.IMAGE,
            DocumentFormatRegistry.Family.BINARY -> throw IllegalArgumentException("No local text extractor for ${format.extension}")
        }
        return text.replace("\u0000", "").trim().take(maxChars)
    }

    private fun readText(file: File, maxChars: Int): String = file.inputStream().bufferedReader(Charsets.UTF_8).use { reader ->
        readBounded(reader, maxChars)
    }

    private fun extractZipXml(file: File, family: DocumentFormatRegistry.Family, maxChars: Int): String {
        val output = StringBuilder()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .take(MAX_ZIP_ENTRIES + 1)
                .toList()
            require(entries.size <= MAX_ZIP_ENTRIES) { "Archive contains too many entries" }
            for (entry in entries) {
                if (output.length >= maxChars) break
                if (!isRelevantEntry(entry.name, family)) continue
                require(entry.size <= MAX_ZIP_ENTRY_BYTES || entry.size < 0) {
                    "Archive entry exceeds the local processing limit"
                }
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                    readBounded(reader, minOf(maxChars - output.length, MAX_ZIP_ENTRY_BYTES.toInt()))
                }
                appendTextNodes(output, xml, maxChars)
            }
        }
        return output.toString().replace(Regex("[ \\t]+"), " ").trim().take(maxChars)
    }

    private fun isRelevantEntry(name: String, family: DocumentFormatRegistry.Family): Boolean = when (family) {
        DocumentFormatRegistry.Family.OOXML ->
            name == "word/document.xml" || name == "word/footnotes.xml" || name == "word/endnotes.xml" ||
                name == "xl/sharedStrings.xml" || name.startsWith("xl/worksheets/") && name.endsWith(".xml") ||
                name.startsWith("ppt/slides/") && name.endsWith(".xml")
        DocumentFormatRegistry.Family.ODF ->
            name == "content.xml" || name == "styles.xml" || name == "meta.xml"
        DocumentFormatRegistry.Family.EPUB ->
            name.startsWith("OEBPS/") || name.startsWith("OPS/") ||
                name.startsWith("Text/") || name.endsWith(".xhtml") || name.endsWith(".html")
        else -> false
    }

    private fun appendTextNodes(output: StringBuilder, xml: String, maxChars: Int) {
        val textPattern = Regex(
            "<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?(?:t|text|p|h[1-6]|title|li)(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z_][A-Za-z0-9_.-]*:)?(?:t|text|p|h[1-6]|title|li)>",
            setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
        )
        textPattern.findAll(xml).forEach { match ->
            if (output.length >= maxChars) return
            val value = decodeXml(stripTags(match.groupValues[1])).trim()
            if (value.isNotEmpty()) output.append(value).append(' ')
        }
        // EPUB XHTML often uses plain body text. Strip tags only after removing
        // script/style blocks; embedded markup is never evaluated.
        if (output.isEmpty() && xml.contains("<html", ignoreCase = true)) {
            val body = xml.replace(Regex("(?is)<(script|style)\\b.*?</\\1>"), "")
            val plain = stripTags(body).replace(Regex("\\s+"), " ").trim()
            if (plain.isNotEmpty()) output.append(plain).append(' ')
        }
    }

    private fun stripTags(value: String): String = value.replace(Regex("<[^>]+>"), " ")

    private fun readBounded(reader: java.io.Reader, limit: Int): String {
        if (limit <= 0) return ""
        val bounded = StringBuilder(minOf(limit, 2_048))
        val buffer = CharArray(minOf(2_048, limit))
        while (bounded.length < limit) {
            val count = reader.read(buffer, 0, minOf(buffer.size, limit - bounded.length))
            if (count <= 0) break
            bounded.append(buffer, 0, count)
        }
        return bounded.toString()
    }

    private fun decodeXml(value: String): String = value
        .replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&apos;", "'")
        .replace("&amp;", "&")
}
