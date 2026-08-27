package com.airi.assistant.ui.plan

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Test

class ExecutionTracePresentationTest {

    @Test
    fun visibleEntries_scopesToExecutionAndSortsBySequence() {
        val entries = listOf(
            event("other", 1, ExecutionTraceKind.PLANNING),
            event("active", 3, ExecutionTraceKind.TOOL_COMPLETED),
            event("active", 1, ExecutionTraceKind.PLANNING),
            event("active", 2, ExecutionTraceKind.STEP_STARTED),
        )

        val visible = ExecutionTracePresentation.visibleEntries(
            events = entries,
            executionId = "active",
            filter = ExecutionTraceFilter.ALL,
        )

        assertEquals(listOf(1L, 2L, 3L), visible.map { it.sequence })
        assertEquals(listOf("active", "active", "active"), visible.map { it.executionId })
    }

    @Test
    fun visibleEntries_filtersToolsAndErrorsWithoutChangingOrder() {
        val entries = listOf(
            event("active", 1, ExecutionTraceKind.PLANNING),
            event("active", 2, ExecutionTraceKind.TOOL_STARTED),
            event("active", 3, ExecutionTraceKind.TOOL_FAILED),
            event("active", 4, ExecutionTraceKind.RECOVERING),
            event("active", 5, ExecutionTraceKind.TOOL_CANCELLED),
        )

        val tools = ExecutionTracePresentation.visibleEntries(entries, "active", ExecutionTraceFilter.TOOLS)
        val errors = ExecutionTracePresentation.visibleEntries(entries, "active", ExecutionTraceFilter.ERRORS)

        assertEquals(listOf(2L, 3L, 5L), tools.map { it.sequence })
        assertEquals(listOf(3L, 4L, 5L), errors.map { it.sequence })
    }

    @Test
    fun unreadCount_ignoresEventsOutsideActiveExecutionAndObservedRange() {
        val entries = listOf(
            event("active", 1, ExecutionTraceKind.PLANNING),
            event("active", 2, ExecutionTraceKind.STEP_STARTED),
            event("other", 9, ExecutionTraceKind.TOOL_FAILED),
            event("active", 3, ExecutionTraceKind.TOOL_COMPLETED),
        )

        assertEquals(1, ExecutionTracePresentation.unreadCount(entries, "active", 2L))
    }

    private fun event(
        executionId: String,
        sequence: Long,
        kind: ExecutionTraceKind,
    ) = ExecutionTraceEvent(
        executionId = executionId,
        sequence = sequence,
        timestampMs = sequence,
        kind = kind,
        summary = "safe summary",
    )
}
