package com.airi.assistant.ai.prompt.budget

import com.airi.assistant.ai.context.ContextBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContributorBudgetPolicyProviderTest {
    private val unloaded = ContextBudget.UNLOADED.availableForContent

    @Test
    fun localProviderKeepsTheOnDeviceWindow() {
        assertEquals(
            ContributorBudgetPolicy.toolCharsCap(unloaded),
            ContributorBudgetPolicy.toolCharsCapFor("local", unloaded),
        )
        assertEquals(
            ContributorBudgetPolicy.toolCharsCap(unloaded),
            ContributorBudgetPolicy.toolCharsCapFor("", unloaded),
        )
    }

    @Test
    fun remoteProviderIsNotLimitedByTheUnloadedLocalWindow() {
        val local = ContributorBudgetPolicy.toolCharsCap(unloaded)
        val remote = ContributorBudgetPolicy.toolCharsCapFor("openai", unloaded)
        assertTrue("remote=$remote local=$local", remote > local * 4)
        assertTrue(remote <= ContributorBudgetPolicy.CLOUD_TOOL_CHARS_MAX)
    }

    @Test
    fun remoteProviderNeverGetsLessThanTheLocalCap() {
        val bigLocal = ContextBudget(65_536).availableForContent
        assertTrue(
            ContributorBudgetPolicy.toolCharsCapFor("gemini", bigLocal) >=
                ContributorBudgetPolicy.toolCharsCap(bigLocal).coerceAtMost(Int.MAX_VALUE),
        )
    }
}
