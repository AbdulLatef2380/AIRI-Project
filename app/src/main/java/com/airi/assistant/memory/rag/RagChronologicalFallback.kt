package com.airi.assistant.memory.rag

import com.airi.assistant.memory.entity.ChatMessage

/** Safe chronological recall used only when semantic embeddings are unavailable. */
internal object RagChronologicalFallback {
    fun select(
        messages: List<ChatMessage>,
        sessionId: String,
        query: String,
        limit: Int,
        projectId: String,
        maxPrivacyLevel: Int,
        nowMs: Long = System.currentTimeMillis(),
    ): List<RetrievedPassage> {
        if (limit <= 0) return emptyList()
        val normalizedQuery = RagQueryPolicy.normalizeQuery(query)
        val recent = messages.asSequence()
            .filter { it.role == "user" || it.role == "assistant" }
            .filter { it.content.isNotBlank() && it.content.trim() != normalizedQuery }
            .filter { it.content.length <= 1_500 }
            .filter { it.privacyLevel <= maxPrivacyLevel }
            .filter { it.expiresAtMs < 0 || it.expiresAtMs > nowMs }
            .filter {
                when (it.memoryScope) {
                    "PROJECT" -> projectId.isNotBlank() && it.projectId == projectId
                    "SESSION" -> it.sessionId == sessionId && sessionId.isNotBlank()
                    else -> false
                }
            }
            .sortedWith(compareByDescending<ChatMessage> { it.timestamp }.thenByDescending { it.id })
            .take(limit)
            .toList()
            .asReversed() // oldest to newest inside the selected recent window

        return recent.mapIndexed { index, message ->
            RetrievedPassage(
                citationId = "recent-${message.id}",
                role = message.role,
                content = message.content,
                // Keep chronological order through RagRetrievalRanker's score sort.
                score = (1f - index * 0.01f).coerceAtLeast(0.5f),
                source = "CHRONOLOGICAL_FALLBACK",
                provenance = "Recent session message",
                scope = message.memoryScope,
                confidence = 0f,
                memoryId = message.id,
            )
        }
    }
}
