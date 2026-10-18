package com.airi.assistant.ui.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedExecutionTraceProjectorTest {
    @Test
    fun `structured lifecycle event wins over duplicate activity event`() {
        val trace = trace(
            executionId = "run-1",
            sequence = 2,
            timestampMs = 1_000,
            kind = ExecutionTraceKind.COMPLETED,
            summary = "Execution completed",
        )
        val activity = ActivityEvent(
            id = "activity-complete",
            timestampMs = 1_250,
            message = "Execution completed",
            executionId = "run-1",
            category = ActivityCategory.ORCHESTRATION,
        )

        val result = project("run-1", listOf(trace), listOf(activity))

        assertEquals(1, result.size)
        assertEquals(trace.eventId, result.single().eventId)
        assertEquals(TaskStatus.COMPLETED, result.single().status)
    }

    @Test
    fun `events from another execution never enter the projection`() {
        val current = trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Read mail")
        val other = trace("run-2", 1, 1_001, ExecutionTraceKind.TOOL_STARTED, "Read private mail")

        val result = project("run-1", listOf(current, other), emptyList())

        assertEquals(listOf(current.eventId), result.map { it.eventId })
    }

    @Test
    fun `different action lifecycle events remain separate`() {
        val started = trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Read mail", "action-1")
        val completed = trace("run-1", 2, 1_100, ExecutionTraceKind.TOOL_COMPLETED, "Read mail", "action-1")

        val result = project("run-1", listOf(started, completed), emptyList())

        assertEquals(listOf(started.eventId, completed.eventId), result.map { it.eventId })
    }

    @Test
    fun `activity events without lifecycle duplicates are preserved`() {
        val activity = ActivityEvent(
            id = "tool-activity",
            timestampMs = 1_000,
            message = "GitHub tool started",
            executionId = "run-1",
            category = ActivityCategory.TOOL,
            severity = ActivitySeverity.INFO,
        )

        val result = project("run-1", emptyList(), listOf(activity))

        assertEquals(1, result.size)
        assertEquals("tool-activity", result.single().eventId)
    }

    @Test
    fun `failed structured event is displayed as failed`() {
        val failed = trace(
            executionId = "run-1",
            sequence = 1,
            timestampMs = 1_000,
            kind = ExecutionTraceKind.TOOL_FAILED,
            summary = "Gmail read failed",
            errorCode = "auth_required",
        )

        val result = project("run-1", listOf(failed), emptyList())

        assertTrue(result.single().title.contains("Gmail"))
        assertEquals(TaskStatus.FAILED, result.single().status)
        assertTrue(result.single().detailLog.orEmpty().contains("auth_required"))
    }

    private fun project(
        executionId: String,
        traces: List<ExecutionTraceEvent>,
        activities: List<ActivityEvent>,
    ) = UnifiedExecutionTraceProjector.project(
        executionId = executionId,
        traceEntries = traces,
        activityEvents = activities,
        planningTaskLabel = "Planning task",
        executionCompletedLabel = "Execution completed",
    )

    private fun trace(
        executionId: String,
        sequence: Long,
        timestampMs: Long,
        kind: ExecutionTraceKind,
        summary: String,
        actionId: String? = null,
        errorCode: String? = null,
    ) = ExecutionTraceEvent(
        executionId = executionId,
        sequence = sequence,
        timestampMs = timestampMs,
        kind = kind,
        summary = summary,
        actionId = actionId,
        errorCode = errorCode,
    )
}
