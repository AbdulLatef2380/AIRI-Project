package com.airi.assistant.ui.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivityMetadata
import com.airi.assistant.ui.activity.ActivityPhase
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.ActivityStatus
import com.airi.assistant.ui.activity.ActivityVisibility
import com.airi.assistant.ui.activity.TaskStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random
import kotlin.system.measureNanoTime

class UnifiedExecutionTraceProjectorTest {
    @Test
    fun `admission planning is not a visible task or task count`() {
        val projection = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-admission",
            traceEntries = listOf(
                trace("run-admission", 1, 1_000, ExecutionTraceKind.PLANNING, "Planning task"),
                trace("run-admission", 2, 1_100, ExecutionTraceKind.TOOL_STARTED, "Read mail", "mail-1"),
            ),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertTrue(projection.admitted)
        assertEquals(UnifiedExecutionMode.TOOL, projection.executionMode)
        assertTrue(projection.events.none { it.category == UnifiedTraceCategory.PLAN })
        assertEquals(0, projection.summary.tasks)
        assertTrue(projection.groups.none { it.category == UnifiedTraceCategory.PLAN })
    }

    @Test
    fun `direct planning-only response has no visible admission projection`() {
        val projection = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-direct",
            traceEntries = listOf(trace("run-direct", 1, 1_000, ExecutionTraceKind.PLANNING, "Planning task")),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertFalse(projection.admitted)
        assertEquals(UnifiedExecutionMode.DIRECT, projection.executionMode)
        assertTrue(projection.events.isEmpty())
        assertFalse(projection.summary.hasContent)
    }

    @Test
    fun `task tree attaches tool to one real step and keeps action identity`() {
        val projection = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-tree",
            traceEntries = listOf(
                trace("run-tree", 1, 1_000, ExecutionTraceKind.STEP_STARTED, "Read the inbox", "step-1"),
                trace("run-tree", 2, 1_100, ExecutionTraceKind.TOOL_STARTED, "Gmail read", "tool-1")
                    .copy(parentId = "step-1"),
                trace("run-tree", 3, 1_200, ExecutionTraceKind.TOOL_COMPLETED, "Gmail read", "tool-1")
                    .copy(parentId = "step-1"),
                trace("run-tree", 4, 1_300, ExecutionTraceKind.STEP_COMPLETED, "Read the inbox", "step-1"),
            ),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        val step = projection.taskTree.single()
        assertEquals(UnifiedTraceCategory.TASK, step.event.category)
        assertEquals("step-1", step.event.actionId)
        assertEquals(1, step.children.size)
        assertEquals("tool-1", step.children.single().event.actionId)
        assertEquals(TaskStatus.COMPLETED, step.children.single().event.status)
        assertEquals(step.event, projection.lastCompletedStep)
    }

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
    fun `internal admission activity is excluded even with a valid execution id`() {
        val internal = ActivityEvent(
            id = "internal-admission",
            message = "runtime_capability_filtered",
            executionId = "run-1",
            category = ActivityCategory.ROUTING,
            metadata = ActivityMetadata(
                phase = ActivityPhase.ADMISSION,
                visibility = ActivityVisibility.INTERNAL,
            ),
        )
        val result = project("run-1", emptyList(), listOf(internal))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `info activity remains running unless terminal status is explicit`() {
        val activity = ActivityEvent(
            id = "info-milestone",
            message = "runtime_model_context_resolved",
            executionId = "run-1",
            category = ActivityCategory.MODEL,
            severity = ActivitySeverity.INFO,
        )
        val result = project("run-1", emptyList(), listOf(activity))
        assertEquals(TaskStatus.RUNNING, result.single().status)
    }

    @Test
    fun `typed completed activity uses explicit terminal status`() {
        val activity = ActivityEvent(
            id = "typed-complete",
            message = "runtime_result_returned",
            executionId = "run-1",
            category = ActivityCategory.ORCHESTRATION,
            severity = ActivitySeverity.INFO,
            metadata = ActivityMetadata(
                phase = ActivityPhase.RESULT,
                status = ActivityStatus.COMPLETED,
                actionId = "action-1",
            ),
        )
        val result = project("run-1", emptyList(), listOf(activity))
        assertEquals(TaskStatus.COMPLETED, result.single().status)
        assertEquals("action-1", result.single().actionId)
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
    fun `summary reports edited files separately from file reads`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-file-edit",
            traceEntries = emptyList(),
            activityEvents = listOf(
                ActivityEvent(
                    timestampMs = 1_000,
                    message = "Artifact updated: calculator.py",
                    executionId = "run-file-edit",
                    category = ActivityCategory.SANDBOX,
                    machineTag = "file_created",
                    metadata = ActivityMetadata(
                        phase = ActivityPhase.ACTION,
                        status = ActivityStatus.COMPLETED,
                    ),
                ),
                ActivityEvent(
                    timestampMs = 1_100,
                    message = "Read file README.md",
                    executionId = "run-file-edit",
                    category = ActivityCategory.SANDBOX,
                    metadata = ActivityMetadata(
                        phase = ActivityPhase.ACTION,
                        status = ActivityStatus.COMPLETED,
                    ),
                ),
            ),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(1, result.summary.filesRead)
        assertEquals(1, result.summary.filesEdited)
    }

    @Test
    fun `compatibility lifecycle events are not rendered beside structured trace`() {
        val activity = ActivityEvent(
            message = "Execution completed",
            executionId = "run-1",
            category = ActivityCategory.ORCHESTRATION,
            machineTag = "execution_lifecycle_compat",
            metadata = ActivityMetadata(
                phase = ActivityPhase.LIFECYCLE,
                status = ActivityStatus.COMPLETED,
            ),
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
    fun `distinct unstructured activity rows with repeated text remain visible`() {
        val first = ActivityEvent(
            id = "activity-1",
            timestampMs = 1_000,
            message = "Reading README.md",
            executionId = "run-1",
            category = ActivityCategory.SANDBOX,
            sequenceNumber = 1L,
        )
        val second = first.copy(id = "activity-2", timestampMs = 1_900, sequenceNumber = 2L)
        val result = project("run-1", emptyList(), listOf(first, second))

        assertEquals(listOf("activity-1", "activity-2"), result.map { it.eventId })
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
    fun `same timestamp activity follows monotonic sequence instead of source list order`() {
        val first = ActivityEvent(
            id = "phase-1",
            timestampMs = 1_000,
            message = "phase one",
            executionId = "run-1",
            category = ActivityCategory.MODEL,
            sequenceNumber = 1L,
        )
        val second = first.copy(id = "phase-2", message = "phase two", sequenceNumber = 2L)

        val result = project("run-1", emptyList(), listOf(second, first))

        assertEquals(listOf("phase-1", "phase-2"), result.map { it.eventId })
        assertEquals(UnifiedTraceCategory.MODEL, result.first().category)
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
    fun `memory activity contributes to memory operations summary`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-memory",
            traceEntries = emptyList(),
            activityEvents = listOf(
                ActivityEvent(
                    id = "memory-read",
                    message = "Memory recalled",
                    executionId = "run-memory",
                    category = ActivityCategory.MEMORY,
                    machineTag = "memory_read",
                ),
            ),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(1, result.summary.memoryOperations)
        assertEquals(UnifiedTraceCategory.MEMORY, result.groups.single().category)
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
            listOf(UnifiedTraceCategory.FILE, UnifiedTraceCategory.COMMAND),
            result.groups.map { it.category },
        )
    }

    @Test
    fun `canonical events are ordered by timestamp then stable sequence`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-order",
            traceEntries = listOf(
                trace("run-order", 3, 2_000, ExecutionTraceKind.TOOL_COMPLETED, "Read file later", "later"),
                trace("run-order", 1, 1_000, ExecutionTraceKind.PLANNING, "Planning task"),
                trace("run-order", 2, 2_000, ExecutionTraceKind.STEP_STARTED, "Read file first", "first"),
            ).shuffled(Random(7)),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(listOf(2L, 3L), result.events.map { it.sequence })
        assertEquals(
            result.events.map { it.eventId }.toSet(),
            result.groups.flatMap { it.events }.map { it.eventId }.toSet(),
        )
    }

    @Test
    fun `activity details are redacted before entering the presentation projection`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-redaction",
            traceEntries = emptyList(),
            activityEvents = listOf(
                ActivityEvent(
                    id = "activity-redacted",
                    message = "Read file report.txt",
                    detail = "Authorization: Bearer top-secret-token",
                    executionId = "run-redaction",
                    category = ActivityCategory.SANDBOX,
                ),
            ),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        val detail = result.events.single().detailLog.orEmpty()
        assertFalse(detail.contains("top-secret-token"))
        assertFalse(detail.contains("Authorization: Bearer"))
    }

    @Test
    fun `file and attachment details never expose extracted content`() {
        val privateArchiveOutput = "RAW_ARCHIVE_CONTENT must never appear in the trace"
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-file-payload",
            traceEntries = listOf(
                trace(
                    executionId = "run-file-payload",
                    sequence = 1,
                    timestampMs = 1_000,
                    kind = ExecutionTraceKind.TOOL_COMPLETED,
                    summary = "Tool call completed: read_file",
                    actionId = "read-file-action",
                    detail = privateArchiveOutput,
                ),
                trace(
                    executionId = "run-file-payload",
                    sequence = 2,
                    timestampMs = 1_500,
                    kind = ExecutionTraceKind.TOOL_COMPLETED,
                    summary = "Read file fallback",
                    detail = privateArchiveOutput,
                ),
            ),
            activityEvents = listOf(
                ActivityEvent(
                    id = "attachment-output",
                    message = "Read file attachment.zip",
                    detail = privateArchiveOutput,
                    executionId = "run-file-payload",
                    category = ActivityCategory.SANDBOX,
                ),
            ),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        val fileEvents = result.events.filter { it.category == UnifiedTraceCategory.FILE }
        assertEquals(3, fileEvents.size)
        assertTrue(fileEvents.all { it.detailLog == null })
        assertTrue(result.events.none { it.title.contains("RAW_ARCHIVE_CONTENT") })
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

    @Test
    fun `tool activity with the same stable action id is projected once`() {
        val activity = ActivityEvent(
            id = "activity-tool-1",
            message = "Tool dispatched: connector_github",
            executionId = "run-1",
            category = ActivityCategory.TOOL,
            machineTag = "tool_dispatch_compat:tool_executed",
            metadata = ActivityMetadata(
                phase = ActivityPhase.ACTION,
                actionId = "tool-1",
            ),
        )
        val result = project(
            "run-1",
            listOf(trace("run-1", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Running tool: connector_github", "tool-1")),
            listOf(activity),
        )

        assertEquals(1, result.size)
        assertEquals("tool-1", result.single().actionId)
    }

    @Test
    fun `tool activity without metadata receives a stable invocation action id`() {
        val result = project(
            "run-1",
            emptyList(),
            listOf(ActivityEvent(
                id = "activity-tool-1",
                message = "Tool dispatched: connector_github",
                executionId = "run-1",
                category = ActivityCategory.TOOL,
            )),
        )

        assertEquals("activity:activity-tool-1", result.single().actionId)
    }

    @Test
    fun `projection builds a parent child task tree`() {
        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-1",
            traceEntries = listOf(
                trace("run-1", 1, 1_000, ExecutionTraceKind.PLANNING, "Planning task"),
                trace("run-1", 2, 1_100, ExecutionTraceKind.STEP_STARTED, "Executing step", "shared-action", detail = "Review repository"),
                trace("run-1", 3, 1_150, ExecutionTraceKind.TOOL_STARTED, "Running tool: read_file", "shared-action"),
                trace("run-1", 4, 1_200, ExecutionTraceKind.TOOL_COMPLETED, "Running tool: read_file", "shared-action"),
                trace("run-1", 5, 1_300, ExecutionTraceKind.STEP_COMPLETED, "Step completed", "shared-action"),
            ),
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        val step = result.taskTree.single()
        assertEquals(UnifiedTraceCategory.TASK, step.event.category)
        assertEquals("Agent step", step.event.title)
        assertEquals(TaskStatus.COMPLETED, step.event.status)
        assertTrue(step.event.detailLog.orEmpty().contains("Review repository"))
        assertEquals(1, step.children.size)
        assertEquals("read_file", step.children.single().event.title)
        assertEquals(TaskStatus.COMPLETED, step.children.single().event.status)
    }

    @Test
    fun `machine tags remain stable while the UI resolver supplies localized titles`() {
        val result = project(
            "run-1",
            emptyList(),
            listOf(ActivityEvent(
                id = "file-activity",
                message = "file_created: /private/path/report.txt",
                executionId = "run-1",
                category = ActivityCategory.SANDBOX,
                machineTag = "file_created",
            )),
        )

        assertTrue(result.single().title.contains("file_created"))
        assertEquals("file_created", result.single().machineTag)

        val localized = UnifiedExecutionTraceProjector.project(
            executionId = "run-1",
            traceEntries = emptyList(),
            activityEvents = listOf(ActivityEvent(
                id = "file-activity",
                message = "file_created: /private/path/report.txt",
                executionId = "run-1",
                category = ActivityCategory.SANDBOX,
                machineTag = "file_created",
            )),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
            titleResolver = { tag, fallback -> if (tag == "file_created") "Create file" else fallback },
        )
        assertEquals("Create file", localized.single().title)
    }

    @Test
    fun `dense agent trace projects within bounded time`() {
        val traces = buildList {
            add(trace("run-dense", 1, 1_000, ExecutionTraceKind.PLANNING, "Planning task"))
            add(trace("run-dense", 2, 1_001, ExecutionTraceKind.STEP_STARTED, "Executing step", "step-1"))
            repeat(1_200) { index ->
                val actionId = "tool-$index"
                val sequence = 3L + index * 2
                add(trace("run-dense", sequence, 1_000L + sequence, ExecutionTraceKind.TOOL_STARTED, "Read file file-$index", actionId))
                add(trace("run-dense", sequence + 1, 1_000L + sequence + 1, ExecutionTraceKind.TOOL_COMPLETED, "Read file file-$index", actionId))
            }
            add(trace("run-dense", 2_404, 4_000, ExecutionTraceKind.STEP_COMPLETED, "Step completed", "step-1"))
        }

        var result: UnifiedExecutionTraceProjection
        val elapsedNanos = measureNanoTime {
            result = UnifiedExecutionTraceProjector.projectProjection(
                executionId = "run-dense",
                traceEntries = traces,
                activityEvents = emptyList(),
                planningTaskLabel = "Planning task",
                executionCompletedLabel = "Execution completed",
            )
        }

        assertEquals(1_201, result.events.size)
        assertEquals(1_200, result.summary.filesRead)
        assertTrue("projection took ${elapsedNanos / 1_000_000} ms", elapsedNanos < 2_000_000_000L)
    }

    @Test
    fun `five thousand out of order lifecycle entries remain deduplicated`() {
        val entries = buildList {
            repeat(2_500) { index ->
                val actionId = "stress-action-$index"
                val sequence = index * 2L + 1L
                add(trace("run-stress", sequence, 10_000L + sequence, ExecutionTraceKind.TOOL_STARTED, "Read file stress-$index", actionId))
                add(trace("run-stress", sequence + 1L, 10_000L + sequence + 1L, ExecutionTraceKind.TOOL_COMPLETED, "Read file stress-$index", actionId))
            }
        }.shuffled(Random(42))

        val result = UnifiedExecutionTraceProjector.projectProjection(
            executionId = "run-stress",
            traceEntries = entries,
            activityEvents = emptyList(),
            planningTaskLabel = "Planning task",
            executionCompletedLabel = "Execution completed",
        )

        assertEquals(2_500, result.events.size)
        assertEquals(2_500, result.events.mapNotNull { it.actionId }.distinct().size)
        assertEquals(2_500, result.groups.single { it.category == UnifiedTraceCategory.FILE }.events.size)
        assertEquals(2_500, result.summary.filesRead)
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

    @Test
    fun `approval interrupt projects as waiting and timeout as failed`() {
        val completedAfterApproval = project(
            "run-approval",
            listOf(
                trace("run-approval", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Tool started", "tool-1"),
                trace("run-approval", 2, 1_001, ExecutionTraceKind.WAITING_FOR_APPROVAL, "Approval required", "tool-1"),
                trace("run-approval", 3, 1_002, ExecutionTraceKind.TOOL_COMPLETED, "Tool completed", "tool-1"),
            ),
            emptyList(),
        )
        assertEquals(1, completedAfterApproval.size)
        assertEquals(TaskStatus.WAITING, completedAfterApproval.single().status)

        val waiting = project(
            "run-approval",
            listOf(trace("run-approval", 1, 1_000, ExecutionTraceKind.WAITING_FOR_APPROVAL, "Approval required", "tool-1")),
            emptyList(),
        )
        assertEquals(TaskStatus.WAITING, waiting.single().status)
        assertEquals(UnifiedTraceCategory.TASK, waiting.single().category)

        val timedOut = project(
            "run-approval",
            listOf(
                trace("run-approval", 1, 1_000, ExecutionTraceKind.TOOL_STARTED, "Tool started", "tool-1"),
                trace("run-approval", 2, 1_001, ExecutionTraceKind.WAITING_FOR_APPROVAL, "Approval required", "tool-1"),
                trace("run-approval", 3, 1_002, ExecutionTraceKind.APPROVAL_TIMEOUT, "Approval expired", "tool-1", errorCode = "approval_timeout"),
            ),
            emptyList(),
        )
        assertEquals(TaskStatus.FAILED, timedOut.single().status)
    }

    private fun trace(
        executionId: String,
        sequence: Long,
        timestampMs: Long,
        kind: ExecutionTraceKind,
        summary: String,
        actionId: String? = null,
        errorCode: String? = null,
        detail: String? = null,
    ) = ExecutionTraceEvent(
        executionId = executionId,
        sequence = sequence,
        timestampMs = timestampMs,
        kind = kind,
        summary = summary,
        detail = detail,
        actionId = actionId,
        errorCode = errorCode,
    )
}
