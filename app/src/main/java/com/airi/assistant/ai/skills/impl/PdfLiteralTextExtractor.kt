package com.airi.assistant.ai.skills.impl

import java.io.File

/** Conservative, bounded extractor for literal PDF string tokens. */
object PdfLiteralTextExtractor {
    const val MAX_SCAN_BYTES = 8 * 1024 * 1024

    fun extract(file: File, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive" }
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(MAX_SCAN_BYTES)
            var offset = 0
            while (offset < buffer.size) {
                val count = input.read(buffer, offset, buffer.size - offset)
                if (count <= 0) break
                offset += count
            }
            buffer.copyOf(offset)
        }
        return extract(bytes, maxChars)
    }

    fun extract(bytes: ByteArray, maxChars: Int): String {
        require(maxChars > 0) { "maxChars must be positive" }
        val raw = bytes.toString(Charsets.ISO_8859_1)
        val matches = Regex("\\(([^()\\\\]{1,2000})\\)").findAll(raw)
        return matches.map { it.groupValues[1] }
            .filter { it.any(Char::isLetterOrDigit) }
            .joinToString(" ")
            .replace(Regex("\\s+"), " ")
            .take(maxChars)
    }
}
