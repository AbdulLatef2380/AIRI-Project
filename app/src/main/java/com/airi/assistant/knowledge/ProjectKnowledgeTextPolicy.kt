package com.airi.assistant.knowledge

/** Pure, bounded text segmentation for local project-file knowledge indexing. */
internal object ProjectKnowledgeTextPolicy {
    private const val TARGET_CHUNK_CHARS = 1_200
    private const val MIN_CHUNK_CHARS = 80
    private const val CHUNK_OVERLAP_CHARS = 120
    private const val MAX_CHUNKS_PER_FILE = 96

    fun chunkText(text: String): List<String> {
        val normalized = text.replace("\u0000", "").trim()
        if (normalized.isBlank()) return emptyList()
        // A user explicitly selected this project file for local indexing. Keep a
        // bounded short note as one lexical chunk instead of reporting a false
        // indexing failure solely because it is below the long-text chunk target.
        if (normalized.length < MIN_CHUNK_CHARS) return listOf(normalized)

        val chunks = mutableListOf<String>()
        var cursor = 0
        while (cursor < normalized.length && chunks.size < MAX_CHUNKS_PER_FILE) {
            val idealEnd = (cursor + TARGET_CHUNK_CHARS).coerceAtMost(normalized.length)
            val end = if (idealEnd == normalized.length) {
                idealEnd
            } else {
                normalized.lastIndexOfAny(charArrayOf('\n', '.', '!', '?', '؟'), idealEnd)
                    .takeIf { it > cursor + MIN_CHUNK_CHARS }
                    ?.plus(1)
                    ?: idealEnd
            }
            val chunk = normalized.substring(cursor, end).trim()
            if (chunk.length >= MIN_CHUNK_CHARS) chunks += chunk
            if (end >= normalized.length) break
            cursor = (end - CHUNK_OVERLAP_CHARS).coerceAtLeast(cursor + 1)
        }
        return chunks
    }
}
