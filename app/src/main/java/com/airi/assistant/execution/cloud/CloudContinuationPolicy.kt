package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.CompletionReason

/** Pure limits and decisions shared by CloudBackend's automatic continuation loop. */
object CloudContinuationPolicy {
    const val MAX_HOPS = 8
    const val MAX_OVERLAP_CHARS = 2_000
    private const val MIN_SAFE_OVERLAP_CHARS = 16
    private const val CONTINUATION_TURN_OVERHEAD_TOKENS = 8
    const val CONTINUATION_PROMPT =
        "Continue the previous answer from exactly where it stopped. Do not repeat any text."

    /** Only an explicit output-length terminal reason may start another provider request. */
    fun shouldContinue(reason: CompletionReason): Boolean = reason == CompletionReason.LENGTH_LIMIT

    /**
     * Remaining response allowance across all hops. Provider usage is preferred,
     * but the existing 4-characters/token estimate prevents unmetered adapters
     * from resetting the full allowance on every hop.
     */
    fun remainingOutputTokens(
        totalRequestedTokens: Int,
        reportedCompletionTokens: Int,
        aggregateText: String,
    ): Int {
        val estimated = estimateTokens(aggregateText)
        val accounted = maxOf(reportedCompletionTokens.coerceAtLeast(0), estimated)
        return (totalRequestedTokens.coerceAtLeast(0) - accounted).coerceAtLeast(0)
    }

    /** Estimate the added prompt material sent on continuation hops. */
    fun continuationPromptEstimate(
        basePromptTokens: Int,
        aggregateText: String,
        continuationPrompt: String = CONTINUATION_PROMPT,
    ): Int {
        val total = basePromptTokens.coerceAtLeast(0).toLong() +
            estimateTokens(aggregateText).toLong() +
            estimateTokens(continuationPrompt).toLong() +
            CONTINUATION_TURN_OVERHEAD_TOKENS
        return total.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * Remove only a substantial, line-aligned duplicate suffix/prefix. Short or
     * mid-line overlaps remain untouched: a duplicate-looking word is not proof
     * that valid answer text may be deleted.
     */
    fun safeOverlapToDrop(previous: String, next: String): Int {
        val max = minOf(previous.length, next.length, MAX_OVERLAP_CHARS)
        for (length in max downTo MIN_SAFE_OVERLAP_CHARS) {
            val start = previous.length - length
            if (start > 0 && previous[start - 1] != '\n' && previous[start - 1] != '\r') continue
            if (!previous.regionMatches(start, next, 0, length)) continue
            val afterOverlap = next.getOrNull(length)
            if (afterOverlap != null && !isBoundary(afterOverlap)) continue
            return length
        }
        return 0
    }

    private fun estimateTokens(text: String): Int =
        if (text.isBlank()) 0 else (text.length + 3) / 4

    private fun isBoundary(char: Char): Boolean = char.isWhitespace() || char in ".!?;:,)]}"
}
