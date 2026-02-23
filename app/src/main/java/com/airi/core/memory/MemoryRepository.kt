package com.airi.core.memory

interface MemoryRepository {

    suspend fun getRecentMessages(limit: Int = 10): List<String>

    suspend fun storeMessage(message: String)

    suspend fun summarize(): String?
}
