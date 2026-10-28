package com.airi.assistant.execution.cloud

import com.airi.assistant.execution.CompletionReason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudContinuationPolicyTest {
    @Test
    fun onlyExplicitLengthLimitAllowsAutomaticContinuation() {
        assertTrue(CloudContinuationPolicy.shouldContinue(CompletionReason.LENGTH_LIMIT))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.CONNECTION_LOST))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.CONTENT_FILTER))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.TOOL_CALLS))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.CANCELLED))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.ERROR))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.UNKNOWN))
        assertFalse(CloudContinuationPolicy.shouldContinue(CompletionReason.STOP))
    }

    @Test
    fun hopCountIsHardBounded() {
        assertEquals(8, CloudContinuationPolicy.MAX_HOPS)
    }

    @Test
    fun remainingBudgetUsesReportedOrEstimatedUsageAndNeverResetsPerHop() {
        assertEquals(
            45,
            CloudContinuationPolicy.remainingOutputTokens(
                totalRequestedTokens = 100,
                reportedCompletionTokens = 30,
                aggregateText = "x".repeat(220), // 55 tokens by the shared 4-char estimate
            ),
        )
        assertEquals(
            0,
            CloudContinuationPolicy.remainingOutputTokens(
                totalRequestedTokens = 100,
                reportedCompletionTokens = 0,
                aggregateText = "x".repeat(400),
            ),
        )
        assertEquals(
            0,
            CloudContinuationPolicy.remainingOutputTokens(
                totalRequestedTokens = 100,
                reportedCompletionTokens = 130,
                aggregateText = "short",
            ),
        )
    }

    @Test
    fun continuationPromptEstimateIncludesAccumulatedAnswerAndNewInstruction() {
        val estimate = CloudContinuationPolicy.continuationPromptEstimate(
            basePromptTokens = 500,
            aggregateText = "x".repeat(400),
            continuationPrompt = "Continue",
        )

        // 500 base + 100 accumulated-answer + 2 instruction + 8 role/template overhead.
        assertEquals(610, estimate)
    }

    @Test
    fun dedupRemovesOnlySubstantialLineAlignedOverlap() {
        val repeatedLine = "The complete previous paragraph is intentionally long enough to test overlap removal."
        val previous = "Intro text\n$repeatedLine"
        val next = "$repeatedLine\nAdditional continuation"

        assertEquals(repeatedLine.length, CloudContinuationPolicy.safeOverlapToDrop(previous, next))
    }

    @Test
    fun dedupPreservesShortOrMidLineRepeatedText() {
        assertEquals(0, CloudContinuationPolicy.safeOverlapToDrop("The answer", "answer continues here"))
        assertEquals(
            0,
            CloudContinuationPolicy.safeOverlapToDrop(
                "This line contains a long repeated phrase",
                "long repeated phrase continues with valid text",
            ),
        )
    }

    @Test
    fun dedupPreservesOverlapThatEndsInsideAWord() {
        assertEquals(
            0,
            CloudContinuationPolicy.safeOverlapToDrop(
                "Intro\nA long duplicated-looking fragment",
                "A long duplicated-looking fragmentSuffix",
            ),
        )
    }
}
