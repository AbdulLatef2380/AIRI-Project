package com.airi.assistant.agent.execution.command

import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.core.planning.PlanStep
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CommandRouterTraceTest {
    @After
    fun tearDown() {
        SubAgentRegistry.resetForTests()
        ExecutionStatusBus.reset()
    }

    @Test
    fun routedCustomActionUsesOwningTraceAdapter() = runBlocking {
        val executionId = "exec-command-router"
        ExecutionStatusBus.onGraphStarted("command-router", 1, executionId, "session-1")
        SubAgentRegistry.initialize(listOf(TraceAgent()))

        val result = CommandRouter.execute(
            PlanStep.Custom("step-1", "research", mapOf("query" to "safe")),
            traceContext = SubAgentContext(
                sessionId = "session-1",
                userId = "test",
                executionId = executionId,
                parentId = "node-1",
                actionId = "command:step-1",
            ),
        )

        assertTrue(result.success)
        val trace = ExecutionStatusBus.traceSnapshot(executionId)
        assertTrue(trace.any { it.machineTag == "tool_output" && it.actionId == "command:step-1" })
        assertTrue(trace.any { it.machineTag == "subagent_completed" && it.kind == ExecutionTraceKind.STEP_COMPLETED })
        assertEquals(1, trace.count { it.machineTag == "subagent_completed" })
    }

    private class TraceAgent : SubAgent {
        override val capability = SubAgentCapability(
            agentId = "trace-agent",
            displayName = "Trace agent",
            description = "test",
            intentKeywords = listOf("research"),
            requiresCloud = false,
            costTier = SubAgentCapability.CostTier.FREE,
        )

        override suspend fun canHandle(input: String, context: SubAgentContext): Boolean =
            input.contains("research")

        override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
            emit(AgentEvent.ToolCall("lookup", mapOf("query" to "safe")))
            emit(AgentEvent.PartialResult("partial"))
            emit(AgentEvent.Complete("completed", durationMs = 5L))
        }
    }
}
