package com.airi.assistant.agent.subagent

import com.airi.assistant.core.ExecutionStatusBus
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentTraceAdapterTest {
    @After
    fun tearDown() {
        ExecutionStatusBus.reset()
    }

    @Test
    fun mapsProgressToolOutputAndCompletionToOwningTrace() {
        val executionId = "exec-subagent-test"
        ExecutionStatusBus.onGraphStarted("test", 1, executionId, "session-1")
        val context = SubAgentContext(
            sessionId = "session-1",
            userId = "test",
            executionId = executionId,
            parentId = "step-1",
            actionId = "subagent:step-1",
        )
        val adapter = SubAgentTraceAdapter(context, "research_agent")

        adapter.accept(AgentEvent.Progress("safe progress", 25, "search"))
        adapter.accept(AgentEvent.ToolCall("search_tool", mapOf("query" to "redacted")))
        adapter.accept(AgentEvent.PartialResult("x".repeat(600)))
        adapter.accept(AgentEvent.Complete("final result", 42L, listOf("search_tool")))

        val trace = ExecutionStatusBus.traceSnapshot(executionId)
        assertTrue(trace.any { it.machineTag == "subagent_progress" && it.parentId == "step-1" })
        assertTrue(trace.any { it.machineTag == "tool_output" && it.actionId == "subagent:step-1" })
        assertTrue(trace.any { it.machineTag == "subagent_completed" && it.kind == ExecutionTraceKind.STEP_COMPLETED })
        assertTrue(trace.filter { it.machineTag == "tool_output" }.all { it.outputSummary.orEmpty().length <= 2_000 })
        assertEquals(1, trace.count { it.machineTag == "subagent_completed" })
    }

    @Test
    fun cancellationIsTypedAndDoesNotBecomeFailure() {
        val executionId = "exec-subagent-cancel"
        ExecutionStatusBus.onGraphStarted("test", 1, executionId, "session-1")
        val context = SubAgentContext(
            sessionId = "session-1",
            userId = "test",
            executionId = executionId,
            parentId = "step-1",
            actionId = "subagent:step-1",
        )
        SubAgentTraceAdapter(context, "research_agent").cancelled()

        val trace = ExecutionStatusBus.traceSnapshot(executionId)
        assertTrue(trace.any { it.machineTag == "subagent_cancelled" && it.kind == ExecutionTraceKind.TOOL_CANCELLED })
        assertTrue(trace.none { it.machineTag == "subagent_cancelled" && it.kind == ExecutionTraceKind.TOOL_FAILED })
    }
}
