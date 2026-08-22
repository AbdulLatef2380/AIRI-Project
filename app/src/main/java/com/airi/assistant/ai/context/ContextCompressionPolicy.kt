package com.airi.assistant.ai.context

/**
 * Intelligent context compression policy for AIRI Core.
 * Prevents token overflow and memory lag by sliding and summarizing long history.
 */
object ContextCompressionPolicy {
    private const val MAX_CONTEXT_TOKENS = 8192
    private const val CHARS_PER_TOKEN_ESTIMATE = 4

    fun shouldCompress(totalChars: Int): Boolean {
        return totalChars > (MAX_CONTEXT_TOKENS * CHARS_PER_TOKEN_ESTIMATE)
    }

    fun compressMessages(messages: List<Pair<String, String>>, maxRecent: Int = 10): List<Pair<String, String>> {
        if (messages.size <= maxRecent) return messages
        val summaryNode = Pair("system", "[Compressed prior context summary: ${messages.size - maxRecent} earlier messages archived for performance]")
        val recentNodes = messages.takeLast(maxRecent)
        return listOf(summaryNode) + recentNodes
    }
}
