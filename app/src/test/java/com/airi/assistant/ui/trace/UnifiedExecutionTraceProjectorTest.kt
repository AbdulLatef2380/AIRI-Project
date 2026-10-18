package com.airi.assistant.ui.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun `blank execution id returns an empty projection without leaking events`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "",
            traceEntries = listOf(trace("run-1", 1, 1_000, ExecutionTraceKind.COMPLETED, "done")),
            activityEvents = listOf(ActivityEvent(message = "leak", executionId = "run-1", category = ActivityCategory.TOOL)),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertTrue(result.events.isEmpty())
        assertTrue(result.groups.isEmpty())
        assertFalse(result.summary.hasContent)
    }

    @Test
    fun `events without execution ownership are excluded`() {
        val activity = ActivityEvent(message = "unowned", category = ActivityCategory.TOOL)
        val result = project("run-1", emptyList(), listOf(activity))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `blank structured summaries are excluded`() {
        val result = project(
            "run-1",
            listOf(trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "   ")),
            emptyList(),
        )

        assertTrue(result.isEmpty())
    }

    @Test
    fun `duplicate unstructured activity rows within the window collapse`() {
        val first = ActivityEvent(
            id = "activity-1",
            timestampMs = 1_000,
            message = "Reading README.md",
            executionId = "run-1",
            category = ActivityCategory.SANDBOX,
        )
        val second = first.copy(id = "activity-2", timestampMs = 1_900)
        val result = project("run-1", emptyList(), listOf(first, second))

        assertEquals(listOf("activity-1"), result.map { it.eventId })
    }

    @Test
    fun `similar activity after the dedupe window remains visible`() {
        val first = ActivityEvent(
            id = "activity-1",
            timestampMs = 1_000,
            message = "Reading README.md",
            executionId = "run-1",
            category = ActivityCategory.SANDBOX,
        )
        val second = first.copy(id = "activity-2", timestampMs = 2_501)
        val result = project("run-1", emptyList(), listOf(first, second))

        assertEquals(listOf("activity-1", "activity-2"), result.map { it.eventId })
    }

    @Test
    fun `action with only a start stays running`() {
        val result = project(
            "run-1",
            listOf(trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Search web", "search-1")),
            emptyList(),
        )

        assertEquals(TaskStatus.RUNNING, result.single().status)
        assertEquals(UnifiedTraceCategory.SEARCH, result.single().category)
    }

    @Test
    fun `failed terminal action keeps error and normalized duration`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-1",
            traceEntries = listOf(
                trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Run terminal", "cmd-1"),
                trace("run-1", 2, 1_100, ExecutionTraceKind.TOOL_FAILED, "Run terminal", "cmd-1", "AUTH_REQUIRED")
                    .copy(durationMs = -20L),
            ),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        val event = result.events.single()
        assertEquals(TaskStatus.FAILED, event.status)
        assertEquals(UnifiedTraceCategory.COMMAND, event.category)
        assertTrue(event.detailLog.orEmpty().contains("auth_required"))
        assertTrue(event.detailLog.orEmpty().contains("0 ms"))
    }

    @Test
    fun `activity categories infer file command and search groups`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-1",
            traceEntries = emptyList(),
            activityEvents = listOf(
                ActivityEvent(message = "Read file notes.md", executionId = "run-1", category = ActivityCategory.SANDBOX),
                ActivityEvent(message = "terminal command completed", executionId = "run-1", category = ActivityCategory.SYSTEM),
                ActivityEvent(message = "web search completed", executionId = "run-1", category = ActivityCategory.TOOL),
            ),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(1, result.summary.filesRead)
        assertEquals(1, result.summary.commandsExecuted)
        assertEquals(1, result.summary.searches)
        assertTrue(result.groups.map { it.category }.containsAll(listOf(
            UnifiedTraceCategory.FILE,
            UnifiedTraceCategory.COMMAND,
            UnifiedTraceCategory.SEARCH,
        )))
    }

    @Test
    fun `groups follow stable visual order regardless of event arrival order`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-1",
            traceEntries = listOf(
                trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_COMPLETED, "Run gradle command", "cmd"),
                trace("run-1", 2, 1_100, ExecutionTraceKind.TOOL_COMPLETED, "Read file a.md", "file"),
                trace("run-1", 3, 1_200, ExecutionTraceKind.PLANNING, "Planning task"),
            ),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(
            listOf(UnifiedTraceCategory.PLAN, UnifiedTraceCategory.FILE, UnifiedTraceCategory.COMMAND),
            result.groups.map { it.category },
        )
    }

    @Test
    fun `tool compatibility events are ignored when structured actions exist`() {
        val activity = ActivityEvent(
            message = "Tool dispatched: web_search",
            executionId = "run-1",
            category = ActivityCategory.TOOL,
            machineTag = "tool_dispatch_compat:page_viewed",
        )
        val result = project(
            "run-1",
            listOf(trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Running tool: web_search", "tool-1")),
            listOf(activity),
        )

        assertEquals(1, result.size)
        assertEquals("run-1:event:1", result.single().eventId)
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
