package com.airi.assistant.ui.trace

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import com.airi.assistant.execution.privacy.PrivacyGuard
import com.airi.assistant.security.CommandRedactor
import com.airi.assistant.ui.activity.ActivityEvent
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.TaskStatus
import com.airi.assistant.ui.activity.sanitizeActivityEventForDisplay

/**
 * One user-facing event after execution-trace and activity sources have been merged.
 *
 * The projection deliberately keeps the execution/action correlation fields so the UI does not
 * have to infer identity from visible text. Activity events without a structured action id are
 * retained as secondary events, except when they duplicate a lifecycle event already emitted by
 * the structured execution trace.
 */
data class UnifiedExecutionTraceEvent(
    val executionId: String,
    val eventId: String,
    val actionId: String?,
    val sequence: Long,
    val timestampMs: Long,
    val title: String,
    val status: TaskStatus,
    val detailLog: String?,
)

internal object UnifiedExecutionTraceProjector {
    private const val DUPLICATE_WINDOW_MS = 1_500L

    fun project(
        executionId: String,
        traceEntries: List<ExecutionTraceEvent>,
        activityEvents: List<ActivityEvent>,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): List<UnifiedExecutionTraceEvent> {
        if (executionId.isBlank()) return emptyList()

        val candidates = buildList {
            traceEntries
                .asSequence()
                .filter { it.executionId == executionId }
                .forEach { add(it.toUnifiedEvent(planningTaskLabel, executionCompletedLabel)) }
            activityEvents
                .asSequence()
                .filter { it.executionId == executionId }
                .map { sanitizeActivityEventForDisplay(it) }
                .forEachIndexed { index, event ->
                    add(event.toUnifiedEvent(index.toLong(), planningTaskLabel, executionCompletedLabel))
                }
        }.sortedWith(compareBy<ProjectionCandidate> { it.event.timestampMs }.thenBy { it.event.sequence })

        val projected = mutableListOf<ProjectionCandidate>()
        candidates.forEach { candidate ->
            val previousIndex = projected.indexOfLast { previous ->
                previous.dedupeKey != null &&
                    previous.dedupeKey == candidate.dedupeKey &&
                    kotlin.math.abs(previous.event.timestampMs - candidate.event.timestampMs) <= DUPLICATE_WINDOW_MS
            }
            if (previousIndex == -1) {
                projected += candidate
            } else if (candidate.sourcePriority < projected[previousIndex].sourcePriority) {
                // Structured lifecycle data is authoritative over the compatibility activity feed.
                projected[previousIndex] = candidate
            }
        }

        return projected
            .sortedWith(compareBy<ProjectionCandidate> { it.event.timestampMs }.thenBy { it.event.sequence })
            .map { it.event }
    }

    private data class ProjectionCandidate(
        val event: UnifiedExecutionTraceEvent,
        val dedupeKey: String?,
        val sourcePriority: Int,
    )

    private fun ExecutionTraceEvent.toUnifiedEvent(
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): ProjectionCandidate {
        val safeSummary = localizeLifecycleSummary(summary, planningTaskLabel, executionCompletedLabel)
        val details = buildList {
            detail?.takeIf { it.isNotBlank() }?.let { add(safeTraceText(it)) }
            errorCode?.let { add("Error code: ${safeTraceText(it)}") }
            durationMs?.let { add("Duration: ${it.coerceAtLeast(0L)} ms") }
        }.joinToString("\n").takeIf { it.isNotBlank() }

        return ProjectionCandidate(
            event = UnifiedExecutionTraceEvent(
                executionId = executionId,
                eventId = eventId,
                actionId = actionId,
                sequence = sequence,
                timestampMs = timestampMs,
                title = safeSummary,
                status = kind.toTaskStatus(),
                detailLog = details,
            ),
            dedupeKey = lifecycleDedupeKey(kind, summary),
            sourcePriority = 0,
        )
    }

    private fun ActivityEvent.toUnifiedEvent(
        sequence: Long,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): ProjectionCandidate {
        val title = localizeLifecycleSummary(message, planningTaskLabel, executionCompletedLabel)
        return ProjectionCandidate(
            event = UnifiedExecutionTraceEvent(
                executionId = executionId.orEmpty(),
                eventId = id,
                actionId = null,
                sequence = sequence,
                timestampMs = timestampMs,
                title = title,
                status = when (severity) {
                    ActivitySeverity.INFO -> TaskStatus.COMPLETED
                    ActivitySeverity.WARN -> TaskStatus.PENDING
                    ActivitySeverity.ERROR -> TaskStatus.FAILED
                },
                detailLog = detail?.takeIf { it.isNotBlank() },
            ),
            dedupeKey = lifecycleDedupeKey(null, message),
            sourcePriority = 1,
        )
    }

    private fun lifecycleDedupeKey(kind: ExecutionTraceKind?, summary: String): String? {
        val normalized = summary.trim().lowercase()
        return when {
            kind == ExecutionTraceKind.PLANNING || normalized == "planning task" || normalized.startsWith("planning:") -> "lifecycle:planning"
            kind == ExecutionTraceKind.COMPLETED || normalized == "execution completed" -> "lifecycle:completed"
            kind == ExecutionTraceKind.FAILED || normalized.startsWith("execution failed") -> "lifecycle:failed"
            kind == ExecutionTraceKind.CANCELLED || normalized.startsWith("execution cancelled") -> "lifecycle:cancelled"
            else -> null
        }
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
        ExecutionTraceKind.TOOL_CANCELLED,
        ExecutionTraceKind.CANCELLED -> TaskStatus.PENDING
    }

    private fun localizeLifecycleSummary(
        value: String,
        planningTaskLabel: String,
        executionCompletedLabel: String,
    ): String = when (value.trim()) {
        "Planning task" -> planningTaskLabel
        "Execution completed" -> executionCompletedLabel
        else -> safeTraceText(value)
    }

    private fun safeTraceText(value: String): String =
        PrivacyGuard.redactForTrace(CommandRedactor.redact(value), maximumChars = 1_000)
}
