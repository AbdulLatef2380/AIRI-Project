package com.airi.assistant.execution

/** Estimates the larger of the local and stateless-cloud prompt projections. */
internal object AgentPromptTokenEstimator {
    fun estimate(
        systemPrompt: String,
        localPrompt: String,
        projection: ConversationRequestProjection,
    ): Int {
        val localChars = systemPrompt.length.toLong() + localPrompt.length
        val remoteChars = systemPrompt.length.toLong() + projection.prompt.length +
            projection.conversationHistory.sumOf { turn ->
                turn.role.length.toLong() + turn.content.length + 4L
            }
        val chars = maxOf(localChars, remoteChars)
        return ((chars + 3L) / 4L).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}
