package com.airi.core.attachments

object StructuredTextChunker {

    fun split(
        attachmentId: String,
        text: String,
        mimeType: String,
        maxChunkChars: Int,
        section: String? = null
    ): List<TextAttachmentChunk> {
        require(maxChunkChars > 0) { "Maximum chunk size must be positive." }
        if (text.isBlank()) return emptyList()

        val chunks = mutableListOf<TextAttachmentChunk>()
        var currentStart = 0
        var cursor = 0
        var chunkIndex = 0

        while (cursor < text.length) {
            val candidateEnd = (currentStart + maxChunkChars).coerceAtMost(text.length)
            val end = if (candidateEnd == text.length) candidateEnd else boundaryBefore(text, currentStart, candidateEnd)
            val normalizedEnd = if (end <= currentStart) candidateEnd else end
            val chunkText = text.substring(currentStart, normalizedEnd).trim()
            if (chunkText.isNotBlank()) {
                val leadingWhitespace = text.substring(currentStart, normalizedEnd).indexOf(chunkText)
                val start = currentStart + leadingWhitespace.coerceAtLeast(0)
                chunks += TextAttachmentChunk(
                    attachmentId = attachmentId,
                    chunkId = "$attachmentId-$chunkIndex",
                    startOffset = start,
                    endOffsetExclusive = start + chunkText.length,
                    section = section,
                    mimeType = mimeType,
                    text = chunkText
                )
                chunkIndex++
            }
            currentStart = normalizedEnd
            cursor = normalizedEnd
        }
        return chunks
    }

    private fun boundaryBefore(text: String, start: Int, end: Int): Int {
        val candidates = listOf("\n\n", "\n", " ")
        return candidates.asSequence()
            .map { marker -> text.lastIndexOf(marker, end - 1) }
            .filter { index -> index > start }
            .maxOrNull()
            ?.let { index -> if (text[index] == '\n') index + 1 else index + 1 }
            ?: end
    }
}
