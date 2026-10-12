package com.airi.assistant.ui.plan

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind

enum class ExecutionLogStage {
    UNDERSTANDING,
    CAPABILITY_CHECK,
    TOOL_SELECTION,
    CONNECTOR,
    EXECUTION,
    RESULT,
    REVIEW,
    COMPLETED,
}

enum class ExecutionLogStatus {
    ACTIVE,
    SUCCESS,
    WARNING,
    FAILED,
    CANCELLED,
}

data class ExecutionLogEntry(
    val eventId: String,
    val executionId: String,
    val sequence: Long,
    val timestampMs: Long,
    val stage: ExecutionLogStage,
    val status: ExecutionLogStatus,
    val title: String,
    val detail: String?,
    val toolName: String?,
    val durationMs: Long?,
)

/** Converts runtime trace events into one bounded, user-facing log contract. */
object ExecutionLogProjection {
    fun project(events: List<ExecutionTraceEvent>): List<ExecutionLogEntry> = events
        .sortedBy { it.sequence }
        .map(::project)

    fun project(event: ExecutionTraceEvent): ExecutionLogEntry {
        val toolName = event.summary.substringAfter(':', missingDelimiterValue = "")
            .trim()
            .takeIf { it.isNotBlank() }
        val (stage, status) = when (event.kind) {
            ExecutionTraceKind.PLANNING -> ExecutionLogStage.UNDERSTANDING to ExecutionLogStatus.ACTIVE
            ExecutionTraceKind.RUNTIME_SIGNAL -> ExecutionLogStage.CAPABILITY_CHECK to ExecutionLogStatus.ACTIVE
            ExecutionTraceKind.STEP_STARTED -> ExecutionLogStage.EXECUTION to ExecutionLogStatus.ACTIVE
            ExecutionTraceKind.STEP_COMPLETED -> ExecutionLogStage.EXECUTION to ExecutionLogStatus.SUCCESS
            ExecutionTraceKind.TOOL_STARTED -> ExecutionLogStage.TOOL_SELECTION to ExecutionLogStatus.ACTIVE
            ExecutionTraceKind.TOOL_OUTPUT -> ExecutionLogStage.RESULT to ExecutionLogStatus.ACTIVE
            ExecutionTraceKind.TOOL_COMPLETED -> ExecutionLogStage.RESULT to ExecutionLogStatus.SUCCESS
            ExecutionTraceKind.TOOL_FAILED -> ExecutionLogStage.RESULT to ExecutionLogStatus.FAILED
            ExecutionTraceKind.TOOL_CANCELLED -> ExecutionLogStage.RESULT to ExecutionLogStatus.CANCELLED
            ExecutionTraceKind.RECOVERING -> ExecutionLogStage.REVIEW to ExecutionLogStatus.WARNING
            ExecutionTraceKind.REFLECTING -> ExecutionLogStage.REVIEW to ExecutionLogStatus.ACTIVE
            ExecutionTraceKind.COMPLETED -> ExecutionLogStage.COMPLETED to ExecutionLogStatus.SUCCESS
            ExecutionTraceKind.FAILED -> ExecutionLogStage.COMPLETED to ExecutionLogStatus.FAILED
            ExecutionTraceKind.CANCELLED -> ExecutionLogStage.COMPLETED to ExecutionLogStatus.CANCELLED
        }
        return ExecutionLogEntry(
            eventId = event.eventId,
            executionId = event.executionId,
            sequence = event.sequence,
            timestampMs = event.timestampMs,
            stage = stage,
            status = status,
            title = event.summary,
            detail = event.detail,
            toolName = toolName,
            durationMs = event.durationMs,
        )
    }
}
