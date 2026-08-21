package com.airi.core.attachments

data class SelectedTextChunk(
    val chunk: TextAttachmentChunk,
    val estimatedTokens: Int,
    val relevance: Int
)

object TextChunkSelector {

    fun select(
        query: String,
        chunks: List<TextAttachmentChunk>,
        tokenBudget: Int,
        estimateTokens: (String) -> Int
    ): List<SelectedTextChunk> {
        require(tokenBudget >= 0) { "Token budget cannot be negative." }
        if (tokenBudget == 0 || chunks.isEmpty()) return emptyList()

        val queryTerms = query.lowercase().split(TERM_SEPARATOR).filter { it.length >= 2 }.toSet()
        val seenContent = mutableSetOf<String>()
        var remaining = tokenBudget

        return chunks.map { chunk ->
            val normalized = chunk.text.trim()
            val relevance = normalized.lowercase().split(TERM_SEPARATOR).count { it in queryTerms }
            Triple(chunk, estimateTokens(normalized).coerceAtLeast(1), relevance)
        }.filter { (chunk, _, _) -> seenContent.add(chunk.text.trim()) }
            .sortedWith(compareByDescending<Triple<TextAttachmentChunk, Int, Int>> { it.third }.thenBy { it.first.chunkId })
            .mapNotNull { (chunk, estimatedTokens, relevance) ->
                if (estimatedTokens > remaining) return@mapNotNull null
                remaining -= estimatedTokens
                SelectedTextChunk(chunk, estimatedTokens, relevance)
            }
    }

    private val TERM_SEPARATOR = Regex("[^\\p{L}\\p{N}_]+")
}
