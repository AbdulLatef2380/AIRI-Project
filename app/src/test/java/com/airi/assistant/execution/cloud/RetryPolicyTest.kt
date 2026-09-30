package com.airi.assistant.execution.cloud

import org.junit.Assert.assertEquals
import org.junit.Test

class RetryPolicyTest {
    @Test
    fun keepsConfiguredRetriesForOrdinaryPrompts() {
        assertEquals(3, RetryPolicy.maxAttemptsForPrompt(4_000, 3))
    }

    @Test
    fun limitsRetriesForLargePromptsAndDisablesExpensiveReplayForVeryLargePrompts() {
        assertEquals(2, RetryPolicy.maxAttemptsForPrompt(12_000, 3))
        assertEquals(1, RetryPolicy.maxAttemptsForPrompt(32_000, 3))
    }

    @Test
    fun configuredSingleAttemptRemainsSingle() {
        assertEquals(1, RetryPolicy.maxAttemptsForPrompt(100, 1))
    }
}
