package com.airi.assistant.ui.trace

import com.airi.assistant.core.AgentEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEventTraceAdapterTest {
    private fun event(
        id: String,
        sequence: Long,
        type: AgentEvent.Type,
        errorCode: String? = null,
    ) = AgentEvent(
        eventId = id,
        executionId = "exec-1",
        sessionId = "session-1",
        sequence = sequence,
        timestampMs = sequence,
        type = type,
        title = id,
        errorCode = errorCode,
    )

    @Test fun canonicalEventsBecomeOrderedTraceEventsWithTypedOutcomes() {
        val rows = AgentEventTraceAdapter.toExecutionTrace(
            listOf(
                event("final", 3, AgentEvent.Type.FINAL),
                event("tool", 2, AgentEvent.Type.TOOL_RESULT, "INVALID_ARGUMENTS"),
                event("start", 1, AgentEvent.Type.TOOL_CALL),
            ),
        )

        assertEquals(listOf("start", "tool", "final"), rows.map { it.summary })
        assertEquals(ExecutionTraceKind.TOOL_STARTED, rows[0].kind)
        assertEquals(ExecutionTraceKind.TOOL_FAILED, rows[1].kind)
        assertEquals(ExecutionTraceKind.COMPLETED, rows[2].kind)
        assertTrue(rows.all { it.executionId == "exec-1" })
    }

    @Test fun adapterUsesExactCanonicalKindWhenCompatibilityTypeIsCoarse() {
        val event = event("requested", 7, AgentEvent.Type.TOOL_CALL).copy(
            traceKind = ExecutionTraceKind.TOOL_REQUESTED,
        )
        val row = AgentEventTraceAdapter.toExecutionTrace(listOf(event)).single()
        assertEquals(ExecutionTraceKind.TOOL_REQUESTED, row.kind)
        assertEquals(7L, row.sequence)
    }
}
