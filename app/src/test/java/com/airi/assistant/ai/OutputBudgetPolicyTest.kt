package com.airi.assistant.ai

import com.airi.assistant.ai.context.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutputBudgetPolicyTest {
    @Test fun analyticalRequestsReceiveTheLargestBudget() {
        assertEquals(4096, OutputBudgetPolicy.requestedTokens("analyze this in detail", QueryType.ANALYTICAL))
    }

    @Test fun localBudgetNeverExceedsRemainingContext() {
        val budget = ContextBudget(2048)
        val result = OutputBudgetPolicy.effectiveTokens("explain", QueryType.ANALYTICAL, 2000, "local", budget)
        assertTrue(result <= 1)
        assertTrue(result >= 1)
    }

    @Test fun cloudBudgetKeepsRequestedPolicy() {
        assertEquals(4096, OutputBudgetPolicy.effectiveTokens("analyze this in detail", QueryType.ANALYTICAL, 9000, "openai", ContextBudget.UNLOADED))
    }
}
