package com.airi.assistant.ui.plan

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind

/** User-selectable, presentation-only trace categories. */
enum class ExecutionTraceFilter {
    ALL,
    PLANNING,
    STEPS,
    TOOLS,
    ERRORS,
}

enum class ExecutionTraceSummaryStatus { RUNNING, COMPLETED, FAILED, CANCELLED }

/**
 * Pure presentation policy for an execution-owned trace.
 *
 * The policy accepts only events that belong to [executionId], preserves the
 * buffer sequence order, and never recreates execution or tool state.
 */
object ExecutionTracePresentation {
    fun latestActiveEvent(events: List<ExecutionTraceEvent>): ExecutionTraceEvent? =
        events.lastOrNull()?.takeIf { event ->
            event.kind in setOf(
                ExecutionTraceKind.PLANNING,
                ExecutionTraceKind.STEP_STARTED,
                ExecutionTraceKind.TOOL_STARTED,
                ExecutionTraceKind.RECOVERING,
                ExecutionTraceKind.REFLECTING,
            )
        }

    fun summaryStatus(
        events: List<ExecutionTraceEvent>,
        isWorking: Boolean,
    ): ExecutionTraceSummaryStatus {
        if (isWorking) return ExecutionTraceSummaryStatus.RUNNING
        return when (events.lastOrNull()?.kind) {
            ExecutionTraceKind.FAILED, ExecutionTraceKind.TOOL_FAILED -> ExecutionTraceSummaryStatus.FAILED
            ExecutionTraceKind.CANCELLED, ExecutionTraceKind.TOOL_CANCELLED -> ExecutionTraceSummaryStatus.CANCELLED
            else -> ExecutionTraceSummaryStatus.COMPLETED
        }
    }

    fun visibleEntries(
        events: List<ExecutionTraceEvent>,
        executionId: String,
        filter: ExecutionTraceFilter,
    ): List<ExecutionTraceEvent> = events
        .asSequence()
        .filter { it.executionId == executionId }
        .filter { event -> filter.accepts(event.kind) }
        .sortedBy { it.sequence }
        .toList()

    fun unreadCount(
        events: List<ExecutionTraceEvent>,
        executionId: String,
        observedThroughSequence: Long,
    ): Int = events.count { event ->
        event.executionId == executionId && event.sequence > observedThroughSequence
    }

    private fun ExecutionTraceFilter.accepts(kind: ExecutionTraceKind): Boolean = when (this) {
        ExecutionTraceFilter.ALL -> true
        ExecutionTraceFilter.PLANNING -> kind in setOf(
            ExecutionTraceKind.PLANNING,
            ExecutionTraceKind.REFLECTING,
        )
        ExecutionTraceFilter.STEPS -> kind in setOf(
            ExecutionTraceKind.STEP_STARTED,
            ExecutionTraceKind.STEP_COMPLETED,
            ExecutionTraceKind.RECOVERING,
        )
        ExecutionTraceFilter.TOOLS -> kind in setOf(
            ExecutionTraceKind.TOOL_STARTED,
            ExecutionTraceKind.TOOL_COMPLETED,
            ExecutionTraceKind.TOOL_FAILED,
            ExecutionTraceKind.TOOL_CANCELLED,
        )
        ExecutionTraceFilter.ERRORS -> kind in setOf(
            ExecutionTraceKind.RECOVERING,
            ExecutionTraceKind.TOOL_FAILED,
            ExecutionTraceKind.TOOL_CANCELLED,
            ExecutionTraceKind.FAILED,
            ExecutionTraceKind.CANCELLED,
        )
    }
}
