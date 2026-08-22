package com.airi.assistant.memory.rag

internal object RagQueryPolicy {
    const val DEFAULT_LIMIT = 5
    const val MAX_LIMIT = 5

    fun normalizeQuery(query: String): String = query.trim()

    fun normalizeLimit(limit: Int): Int = limit.coerceIn(1, MAX_LIMIT)

    fun accepts(query: String): Boolean = normalizeQuery(query).isNotEmpty()
}
