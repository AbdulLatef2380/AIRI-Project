package com.airi.assistant.attachments

import com.airi.assistant.ai.skills.impl.PdfLiteralTextExtractor
import java.io.File
import java.util.zip.ZipFile

/**
 * Extracts user-selected document content locally before it is sent to a model.
 * Extraction is bounded and all returned text remains untrusted attachment data.
 */
object AttachmentContentExtractor {
    const val MAX_CHARS = 512_000

    fun supports(mimeType: String, fileName: String): Boolean {
        val mime = mimeType.lowercase()
        val ext = fileName.substringAfterLast('.', "").lowercase()
        return mime == "application/pdf" || ext == "pdf" ||
            ext in setOf("docx", "xlsx", "pptx") ||
            mime in setOf(
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            )
    }

    fun extract(file: File, mimeType: String, fileName: String, maxChars: Int = MAX_CHARS): String {
        require(maxChars > 0)
        require(file.isFile && file.length() > 0L) { "Attachment is empty or missing" }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val mime = mimeType.lowercase()
        val text = if (mime == "application/pdf" || ext == "pdf") {
            PdfLiteralTextExtractor.extract(file, maxChars)
        } else {
            extractOpenXml(file, maxChars)
        }
        return text.replace("\u0000", "").trim().take(maxChars)
    }

    private fun extractOpenXml(file: File, maxChars: Int): String {
        val names = listOf(
            "word/document.xml", "word/footnotes.xml", "word/endnotes.xml",
            "xl/sharedStrings.xml", "ppt/slides/slide1.xml"
        )
        val output = StringBuilder()
        ZipFile(file).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory && (
                        entry.name in names ||
                            entry.name.startsWith("word/") && entry.name.endsWith(".xml") ||
                            entry.name.startsWith("xl/worksheets/") && entry.name.endsWith(".xml") ||
                            entry.name.startsWith("ppt/slides/") && entry.name.endsWith(".xml")
                        )
                }
                .toList()
            for (entry in entries) {
                if (output.length >= maxChars) break
                val xml = zip.getInputStream(entry).bufferedReader(Charsets.UTF_8).use { reader ->
                    readBounded(reader, maxChars - output.length)
                }
                // OOXML text is carried primarily in <t> nodes. WordprocessingML
                // normally prefixes that element as <w:t>; accept an optional
                // namespace prefix without evaluating markup or external refs.
                Regex("""<(?:[A-Za-z_][A-Za-z0-9_.-]*:)?t(?:\s[^>]*)?>(.*?)</(?:[A-Za-z_][A-Za-z0-9_.-]*:)?t>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                    .findAll(xml)
                    .forEach { match ->
                        if (output.length < maxChars) {
                            val value = decodeXml(match.groupValues[1]).trim()
                            if (value.isNotEmpty()) output.append(value).append(' ')
                        }
                    }
                if (output.isNotEmpty()) output.append('\n')
            }
        }
        return output.toString().replace(Regex("[ \\t]+"), " ").trim().take(maxChars)
    }

    private fun readBounded(reader: java.io.Reader, limit: Int): String {
        if (limit <= 0) return ""
        val bounded = StringBuilder(limit)
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
