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

/**
 * Pure presentation policy for an execution-owned trace.
 *
 * The policy accepts only events that belong to [executionId], preserves the
 * buffer sequence order, and never recreates execution or tool state.
 */
object ExecutionTracePresentation {
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
