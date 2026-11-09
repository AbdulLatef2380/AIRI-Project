package com.airi.assistant.agent.loop.verification

import com.airi.assistant.ai.CapabilityIntentDetector.Capability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActionCompletionGuardTest {
    private fun guard() = ActionCompletionGuard(setOf(Capability.TERMINAL_EXECUTE), setOf("terminal_execute", "web_search"))

    @Test fun textOnlyReplyToActionRequestIsCorrectedOnce() {
        val g = guard()
        val first = g.onFinalText(emptyList())
        assertTrue(first is ActionCompletionGuard.Verdict.Correct)
        assertTrue((first as ActionCompletionGuard.Verdict.Correct).prompt.contains("terminal_execute"))
        assertEquals(ActionCompletionGuard.Verdict.Accept, g.onFinalText(emptyList()))
    }

    @Test fun matchingToolRunMeansAccept() {
        assertEquals(ActionCompletionGuard.Verdict.Accept, guard().onFinalText(listOf("terminal_execute")))
    }

    @Test fun confirmationRequestCountsAsHandled() {
        assertEquals(ActionCompletionGuard.Verdict.Accept, guard().onFinalText(listOf("ask_confirmation")))
    }

    @Test fun toolNotExposedIsNeverDemanded() {
        val g = ActionCompletionGuard(setOf(Capability.FILE_WRITE), setOf("web_search"))
        assertEquals(ActionCompletionGuard.Verdict.Accept, g.onFinalText(emptyList()))
    }

    @Test fun noActionCapabilityMeansAccept() {
        assertEquals(ActionCompletionGuard.Verdict.Accept, ActionCompletionGuard(emptySet(), setOf("terminal_execute")).onFinalText(emptyList()))
    }
}
