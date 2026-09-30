package com.airi.assistant.ai.prompt

import com.airi.assistant.memory.entity.ChatMessage

/**
 * Selects the uncovered prefix of a session to fold into its accepted summary.
 * The coverage marker is an index into the append-only session history, not the
 * count of messages in the latest incremental batch.
 */
internal object ConversationSummaryWindowPolicy {
    const val RECENT_MESSAGES = 12

    data class Plan(
        val previousSummary: String,
        val olderTurns: List<ChatMessage>,
        val coverageThrough: Int,
    ) {
        val shouldSummarize: Boolean get() = olderTurns.isNotEmpty()
    }

    fun select(
        history: List<ChatMessage>,
        previousSummary: String,
        coveredThrough: Int,
        recentMessages: Int = RECENT_MESSAGES,
    ): Plan {
        val foldThrough = (history.size - recentMessages.coerceAtLeast(0)).coerceAtLeast(0)
        // A coverage marker beyond the current history can only belong to stale
        // data (e.g. a restored session id). Drop that summary rather than
        // silently hiding messages in the new session.
        if (coveredThrough < 0 || coveredThrough > history.size) {
            return Plan("", history.take(foldThrough), foldThrough)
        }
        val alreadyCovered = coveredThrough.coerceAtMost(foldThrough)
        return Plan(
            previousSummary = previousSummary,
            olderTurns = history.subList(alreadyCovered, foldThrough),
            coverageThrough = foldThrough,
        )
    }
}
