package com.airi.assistant.memory.rag

/**
 * Deterministic post-retrieval ordering for already-authorized passages.
 *
 * This component never admits a passage, changes its scope, or rewrites its
 * content. Callers must apply privacy, expiry, project, and prompt-safety
 * filters first. Semantic-message and project-knowledge scores are bounded
 * retrieval signals, so they are ordered together. Durable-memory confidence
 * is not a query-relevance score; durable rows are therefore retained as a
 * fallback in their caller-provided governed order after query-retrieved hits.
 *
 * Exact normalized-content duplicates retain the first passage in that defined
 * ordering. The ranker does not infer or resolve conflicting memories.
 */
internal object RagRetrievalRanker {

    fun rank(passages: List<RetrievedPassage>, limit: Int): List<RetrievedPassage> {
        if (limit <= 0) return emptyList()

        val queryRetrieved = passages
            .asSequence()
            .filterNot(::isDurableMemory)
            .sortedWith(
                compareByDescending<RetrievedPassage> { it.score }
                    .thenByDescending { it.confidence }
                    .thenBy { it.citationId }
            )

        val durableFallback = passages
            .asSequence()
            .filter(::isDurableMemory)

        val seenContent = mutableSetOf<String>()
        return (queryRetrieved + durableFallback)
            .filter { seenContent.add(contentFingerprint(it.content)) }
            .take(limit)
            .toList()
    }

    internal fun contentFingerprint(content: String): String {
        val normalized = buildString(content.length) {
            content.forEach { character ->
                when {
                    character.isLetterOrDigit() -> append(character.lowercaseChar())
                    else -> append(' ')
                }
            }
        }
        return normalized.trim().replace(WHITESPACE, " ")
    }

    private fun isDurableMemory(passage: RetrievedPassage): Boolean = passage.role == "memory"

    private val WHITESPACE = Regex("\\s+")
}
