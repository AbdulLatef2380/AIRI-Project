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

        assertEquals(listOf(started.eventId), result.map { it.eventId })
        assertEquals(TaskStatus.COMPLETED, result.single().status)
    }

    @Test
    fun `different action ids remain separate while each action is collapsed`() {
        val first = trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Read mail", "action-1")
        val firstDone = trace("run-1", 2, 1_100, ExecutionTraceKind.TOOL_COMPLETED, "Read mail", "action-1")
        val second = trace("run-1", 3, 1_200, ExecutionTraceKind.TOOL_STARTED, "Read calendar", "action-2")
        val result = project("run-1", listOf(first, firstDone, second), emptyList())

        assertEquals(listOf(first.eventId, second.eventId), result.map { it.eventId })
        assertEquals(TaskStatus.COMPLETED, result[0].status)
        assertEquals(TaskStatus.RUNNING, result[1].status)
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

    @Test
    fun `projection builds grouped summary from file command and tool actions`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-1",
            traceEntries = listOf(
                trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Read file README.md", "file-1"),
                trace("run-1", 2, 1_100, ExecutionTraceKind.TOOL_COMPLETED, "Read file README.md", "file-1"),
                trace("run-1", 3, 1_200, ExecutionTraceKind.TOOL_COMPLETED, "Run gradle command", "command-1"),
            ),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(2, result.events.size)
        assertEquals(1, result.summary.filesRead)
        assertEquals(1, result.summary.commandsExecuted)
        assertEquals(1, result.groups.count { it.category == UnifiedTraceCategory.FILE })
        assertEquals(1, result.groups.count { it.category == UnifiedTraceCategory.COMMAND })
    }

    @Test
    fun `compatibility lifecycle events are not rendered beside structured trace`() {
        val activity = ActivityEvent(
            message = "Execution completed",
            executionId = "run-1",
            category = ActivityCategory.ORCHESTRATION,
            machineTag = "execution_lifecycle_compat",
        )
        val result = UnifiedExecutionTraceProjector.project(
            executionId = "run-1",
            traceEntries = listOf(trace("run-1", 1, 1_000, ExecutionTraceKind.COMPLETED, "Execution completed")),
            activityEvents = listOf(activity),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(1, result.size)
        assertEquals(TaskStatus.COMPLETED, result.single().status)
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
