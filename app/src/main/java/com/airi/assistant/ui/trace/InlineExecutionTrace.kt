package com.airi.assistant.ui.trace

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.security.CommandRedactor
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentLifecycleTrackerPanel
import com.airi.assistant.ui.activity.AgentTask
import com.airi.assistant.ui.activity.TaskStatus
import com.airi.assistant.ui.activity.sanitizeActivityEventForDisplay

/**
 * Renders the current agent execution trace inline with the assistant turn.
 *
 * This adapter preserves the existing chat integration: structured lifecycle events and
 * tool/connector/skill/runtime activity events are combined into one chronological list. Only
 * sanitized summaries and detail logs are sent to the user-facing panel; private model reasoning
 * and raw credentials are not shown.
 */
@Composable
fun InlineExecutionTrace(
    executionId: String,
    entries: List<ExecutionTraceEvent>,
    isWorking: Boolean,
    activityEvents: List<ActivityEvent> = emptyList(),
    modifier: Modifier = Modifier,
) {
    if (executionId.isBlank()) return

    val scopedActivityEvents = remember(activityEvents, executionId) {
        activityEvents.filter { it.executionId == executionId }
    }
    if (!isWorking && entries.isEmpty() && scopedActivityEvents.isEmpty()) return

    // Stable event IDs become LazyColumn keys. Sorting both sources by their actual event time
    // preserves the order in which Airi performs operations while keeping recompositions cheap.
    val tasks = remember(entries, scopedActivityEvents, executionId) {
        buildList {
            entries.forEach { add(it.toTimestampedTask()) }
            scopedActivityEvents.forEach { add(it.toTimestampedTask()) }
        }
            .sortedBy { it.timestampMs }
            .map { it.task }
    }

    AgentLifecycleTrackerPanel(
        tasks = tasks,
        isWorking = isWorking,
        modifier = modifier,
    )
}

private data class TimestampedTask(
    val timestampMs: Long,
    val task: AgentTask,
)

private fun ExecutionTraceEvent.toTimestampedTask(): TimestampedTask {
    val details = buildList {
        detail?.takeIf { it.isNotBlank() }?.let { add(safeTraceText(it)) }
        errorCode?.let { add("Error code: ${safeTraceText(it)}") }
        durationMs?.let { add("Duration: ${it.coerceAtLeast(0L)} ms") }
    }.joinToString("\n").takeIf { it.isNotBlank() }

    return TimestampedTask(
        timestampMs = timestampMs,
        task = AgentTask(
            id = eventId,
            title = safeTraceText(summary),
            status = kind.toTaskStatus(),
            detailLog = details,
        ),
    )
}

private fun ActivityEvent.toTimestampedTask(): TimestampedTask {
    // Sanitize again at the rendering boundary as defense in depth; events may also be created
    // directly by tests or future producers rather than flowing through AgentActivityBus.emit().
    val safeEvent = sanitizeActivityEventForDisplay(this)
    val details = buildList {
        add("Category: ${safeEvent.category.label}")
        add("Severity: ${safeEvent.severity.name}")
        safeEvent.detail?.takeIf { it.isNotBlank() }?.let { add(it) }
    }.joinToString("\n")

    return TimestampedTask(
        timestampMs = safeEvent.timestampMs,
        task = AgentTask(
            id = safeEvent.id,
            title = safeEvent.message,
            status = when (safeEvent.severity) {
                ActivitySeverity.INFO -> TaskStatus.COMPLETED
                ActivitySeverity.WARN -> TaskStatus.PENDING
                ActivitySeverity.ERROR -> TaskStatus.FAILED
            },
            detailLog = details,
        ),
    )
}

private fun ExecutionTraceKind.toTaskStatus(): TaskStatus = when (this) {
    ExecutionTraceKind.PLANNING -> TaskStatus.PENDING
    ExecutionTraceKind.STEP_STARTED,
    ExecutionTraceKind.TOOL_STARTED,
    ExecutionTraceKind.RECOVERING,
    ExecutionTraceKind.REFLECTING -> TaskStatus.RUNNING
    ExecutionTraceKind.STEP_COMPLETED,
    ExecutionTraceKind.TOOL_COMPLETED,
    ExecutionTraceKind.COMPLETED -> TaskStatus.COMPLETED
    ExecutionTraceKind.TOOL_FAILED,
    ExecutionTraceKind.FAILED -> TaskStatus.FAILED
    // The requested lifecycle model has no CANCELLED state. A cancelled step is therefore
    // represented as not completed, with its event title still making the cancellation explicit.
    ExecutionTraceKind.TOOL_CANCELLED,
    ExecutionTraceKind.CANCELLED -> TaskStatus.PENDING
}

private fun safeTraceText(value: String): String =
    PrivacyGuard.redactForTrace(CommandRedactor.redact(value), maximumChars = 1_000)
