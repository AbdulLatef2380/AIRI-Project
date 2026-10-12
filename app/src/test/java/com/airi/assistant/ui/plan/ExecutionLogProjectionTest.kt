package com.airi.assistant.ui.plan

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionLogProjectionTest {
    @Test
    fun projectMapsRuntimeEventsToUserFacingStagesAndStatuses() {
        val entries = ExecutionLogProjection.project(
            listOf(
                event(1, ExecutionTraceKind.PLANNING, "Planning task"),
                event(2, ExecutionTraceKind.RUNTIME_SIGNAL, "Inspecting runtime capabilities"),
                event(3, ExecutionTraceKind.TOOL_STARTED, "Running tool: gmail_read"),
                event(4, ExecutionTraceKind.TOOL_FAILED, "Tool failed: gmail_read", "Scope missing"),
                event(5, ExecutionTraceKind.COMPLETED, "Execution completed"),
            )
        )

        assertEquals(listOf(ExecutionLogStage.UNDERSTANDING, ExecutionLogStage.CAPABILITY_CHECK, ExecutionLogStage.TOOL_SELECTION, ExecutionLogStage.RESULT, ExecutionLogStage.COMPLETED), entries.map { it.stage })
        assertEquals(ExecutionLogStatus.FAILED, entries[3].status)
        assertEquals("gmail_read", entries[2].toolName)
        assertEquals("Scope missing", entries[3].detail)
        assertEquals(ExecutionLogStatus.SUCCESS, entries.last().status)
    }

    @Test
    fun projectPreservesExecutionOwnershipAndSequence() {
        val entries = ExecutionLogProjection.project(
            listOf(event(4, ExecutionTraceKind.TOOL_COMPLETED, "Tool completed: github_read", executionId = "other")) +
                listOf(event(2, ExecutionTraceKind.STEP_STARTED, "Executing step"), event(1, ExecutionTraceKind.PLANNING, "Planning task"))
        )
        assertEquals(listOf(1L, 2L, 4L), entries.map { it.sequence })
        assertEquals(listOf("active", "active", "other"), entries.map { it.executionId })
        assertTrue(entries.last().title.contains("github_read"))
    }

    private fun event(
        sequence: Long,
        kind: ExecutionTraceKind,
        summary: String,
        detail: String? = null,
        executionId: String = "active",
    ) = ExecutionTraceEvent(executionId, sequence, sequence, kind, summary, detail)
}
