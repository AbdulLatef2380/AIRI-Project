package com.airi.assistant.ui.run

import com.airi.assistant.ai.agent.trace.AgentStep
import com.airi.assistant.ai.agent.trace.AgentStepType
import com.airi.assistant.ai.agent.trace.AgentTrace
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentRunScreenStateTest {
    private fun t(id: String = "r", success: Boolean = true, live: Boolean = false, result: String = "", steps: List<AgentStep> = emptyList()) =
        AgentTrace(id = id, originalInput = "x", success = success, inProgress = live, finalResult = result, steps = steps)

    @Test fun loadingUntilStoreEmits() {
        assertEquals(AgentRunScreenState.Loading, deriveRunScreenState("r", emptyList(), loaded = false))
    }

    @Test fun missingWhenLoadedAndAbsent() {
        assertEquals(AgentRunScreenState.Missing, deriveRunScreenState("r", emptyList(), loaded = true))
    }

    @Test fun offlineWhenAbsentAndNoNetwork() {
        assertEquals(AgentRunScreenState.Offline, deriveRunScreenState("r", emptyList(), loaded = true, online = false))
    }

    @Test fun runningAndWaitingApproval() {
        val live = t(live = true)
        assertTrue(deriveRunScreenState("r", listOf(live), true) is AgentRunScreenState.Running)
        assertTrue(deriveRunScreenState("r", listOf(live), true, waitingApproval = true) is AgentRunScreenState.WaitingApproval)
    }

    @Test fun completedAndFailed() {
        assertTrue(deriveRunScreenState("r", listOf(t()), true) is AgentRunScreenState.Completed)
        val failed = deriveRunScreenState("r", listOf(t(success = false, steps = listOf(AgentStep(0, AgentStepType.TOOL, "x", success = false, error = "boom")))), true)
        assertEquals(false, (failed as AgentRunScreenState.Failed).cancelled)
    }

    @Test fun cancelledDistinguishedFromFailure() {
        val s = deriveRunScreenState("r", listOf(t(success = false, result = "Cancelled")), true)
        assertEquals(true, (s as AgentRunScreenState.Failed).cancelled)
    }
}
