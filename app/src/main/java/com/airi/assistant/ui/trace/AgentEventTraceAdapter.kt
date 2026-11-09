package com.airi.assistant.ui.trace

import com.airi.assistant.core.AgentEvent
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.TraceEventContract

/**
 * Compatibility projection for the old UI projector. AgentEvent remains the only write contract;
 * this adapter exists solely so existing Compose grouping/tree code can consume canonical events.
 */
internal object AgentEventTraceAdapter {
    fun toExecutionTrace(events: List<AgentEvent>): List<ExecutionTraceEvent> =
        events
            .filter { it.executionId.isNotBlank() }
            .distinctBy { it.eventId }
            .sortedWith(compareBy<AgentEvent> { it.timestampMs }.thenBy { it.sequence }.thenBy { it.eventId })
            .map { event ->
                ExecutionTraceEvent(
                    executionId = event.executionId,
                    sequence = event.sequence,
                    timestampMs = event.timestampMs,
                    kind = event.traceKind ?: TraceEventContract.traceKindFor(event.type, event.errorCode),
                    summary = event.title,
                    detail = event.detail,
                    actionId = event.actionId,
                    durationMs = event.durationMs,
                    errorCode = event.errorCode,
                    sessionId = event.sessionId,
                    machineTag = event.machineTag,
                    parentId = event.parentId,
                    inputParams = event.inputParams,
                    outputSummary = event.outputSummary,
                    provenance = event.provenance,
                )
            }

}
