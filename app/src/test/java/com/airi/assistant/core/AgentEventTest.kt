package com.airi.assistant.core

import com.airi.assistant.ai.agent.trace.ModelTurnRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventTest {
    @Test
    fun traceKindsProjectToStableUnifiedTypes() {
        val toolStart = AgentEvent.fromTrace(
            ExecutionTraceEvent(
                executionId = "exec-1",
                sequence = 1,
                timestampMs = 10,
                kind = ExecutionTraceKind.TOOL_STARTED,
                summary = "Running tool",
                actionId = "action-1",
                sessionId = "session-1",
                machineTag = "tool_call",
            ),
        )
        val final = AgentEvent.fromTrace(
            ExecutionTraceEvent(
                executionId = "exec-1",
                sequence = 2,
                timestampMs = 20,
                kind = ExecutionTraceKind.COMPLETED,
                summary = "Completed",
                sessionId = "session-1",
            ),
        )

        assertEquals(AgentEvent.Type.TOOL_CALL, toolStart.type)
        assertEquals(ExecutionTraceKind.TOOL_STARTED, toolStart.traceKind)
        assertEquals(AgentEvent.Type.FINAL, final.type)
        assertEquals("exec-1:event:1", toolStart.eventId)
        assertEquals("session-1", final.sessionId)
    }

    @Test
    fun canonicalEventRetainsExactKindEvenWhenCompatibilityTypeIsCoarse() {
        val requested = AgentEvent.fromTrace(
            ExecutionTraceEvent(
                executionId = "exec-kind",
                sequence = 4,
                timestampMs = 40,
                kind = ExecutionTraceKind.TOOL_REQUESTED,
                summary = "requested",
                actionId = "a-1",
            ),
        )
        assertEquals(AgentEvent.Type.TOOL_CALL, requested.type)
        assertEquals(ExecutionTraceKind.TOOL_REQUESTED, requested.traceKind)
    }

    @Test
    fun modelTurnIsASeparateUnifiedEvent() {
        val event = AgentEvent.modelTurn(
            executionId = "exec-2",
            sessionId = "session-2",
            record = ModelTurnRecord(
                turn = 1,
                timestampMs = 30,
                durationMs = 44,
                providerId = "openai",
                modelId = "gpt",
            ),
        )

        assertEquals(AgentEvent.Type.MODEL_TURN, event.type)
        assertTrue(event.detail!!.contains("openai/gpt"))
        assertEquals(44L, event.durationMs)
    }

    @Test
    fun traceContractCoversEveryCanonicalKindAndKeepsTerminalDistinctFromToolSuccess() {
        ExecutionTraceKind.entries.forEach { kind ->
            assertTrue("missing mapping for $kind", TraceEventContract.agentTypeFor(kind) != null)
        }

        assertEquals(
            AgentEvent.Type.TOOL_RESULT,
            TraceEventContract.agentTypeFor(ExecutionTraceKind.TOOL_COMPLETED),
        )
        assertEquals(
            AgentEvent.Type.FINAL,
            TraceEventContract.agentTypeFor(ExecutionTraceKind.COMPLETED),
        )
        assertEquals(
            ExecutionTraceKind.TOOL_COMPLETED,
            TraceEventContract.traceKindFor(AgentEvent.Type.TOOL_RESULT, null),
        )
        assertEquals(
            ExecutionTraceKind.COMPLETED,
            TraceEventContract.traceKindFor(AgentEvent.Type.FINAL, null),
        )
    }

    @Test
    fun busKeepsAFilteredSnapshotAndCanBeCleared() {
        AgentEventBus.clear()
        val event = AgentEvent(
            eventId = "exec-3:event:1",
            executionId = "exec-3",
            sessionId = "session-3",
            sequence = 1,
            timestampMs = 1,
            type = AgentEvent.Type.PLAN_UPDATED,
            title = "Plan",
        )
        AgentEventBus.emit(event)

        assertEquals(listOf(event), AgentEventBus.snapshot("exec-3"))
        assertTrue(AgentEventBus.snapshot("other").isEmpty())
        AgentEventBus.clear()
        assertTrue(AgentEventBus.recent.value.isEmpty())
    }
}
