package com.airi.assistant.agent.trace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraceStepContractTest {
    @Test
    fun toolStep_can_transition_running_to_done_without_changing_identity() {
        val running = TraceNodeState("node-1", TraceStep.UiInteraction("ينفذ الضغط…"), NodeExecutionState.RUNNING)
        val done = running.copy(executionState = NodeExecutionState.DONE)
        assertEquals(running.id, done.id)
        assertEquals(NodeExecutionState.DONE, done.executionState)
        assertTrue(done.step is TraceStep.UiInteraction)
    }

    @Test
    fun error_step_keeps_original_step_for_user_expansion() {
        val original = TraceStep.WebSearch("AIRI", 0)
        val error = TraceStep.StepError(original, "timeout")
        assertEquals(original, error.originalStep)
        assertEquals("timeout", error.errorMessage)
    }
}
