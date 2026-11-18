package com.airi.assistant.memory.repository

import com.airi.assistant.memory.embedding.EmbeddingService
import com.airi.assistant.memory.entity.ChatMessage

/** Narrow retrieval contract consumed by RAG; durable writes remain in MemoryManager/Room. */
interface RagMemorySource {
    suspend fun getScopedLongTermMemories(
        sessionId: String,
        projectId: String = "",
        maxPrivacyLevel: Int = 1,
        limit: Int = 20,
    ): List<ChatMessage>

    suspend fun isScopedMemoryVisible(
        memoryId: Long,
        sessionId: String,
        projectId: String,
        maxPrivacyLevel: Int,
    ): Boolean

    fun isSemanticMemoryReady(): Boolean

    suspend fun semanticSearch(
        sessionId: String,
        query: String,
        k: Int = 5,
    ): List<EmbeddingService.RankedMessage>

    suspend fun getRecentMessages(sessionId: String, limit: Int = 10): List<ChatMessage>
}
