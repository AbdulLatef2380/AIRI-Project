package com.airi.assistant.agent.loop

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentLoopExecutionContextTest {

    @Test
    fun acceptsOnlyStableIdentifierCoordinatesForRegisteredAgentLoopPrincipal() {
        val context = AgentLoopExecutionContext(
            taskId = "task-1",
            missionId = "mission-1",
            projectId = "project-1",
            runId = "run-1",
            stepId = "calendar_create",
            agentId = AgentLoopExecutionContext.AGENT_LOOP_PRINCIPAL,
            sourceSessionId = "session-1"
        )

        assertTrue(context.isStructurallyValid())
        assertFalse(context.copy(agentId = "calendar_create").isStructurallyValid())
        assertFalse(context.copy(sourceSessionId = "session with prompt text").isStructurallyValid())
        assertFalse(context.copy(projectId = "project/other").isStructurallyValid())
    }
}
