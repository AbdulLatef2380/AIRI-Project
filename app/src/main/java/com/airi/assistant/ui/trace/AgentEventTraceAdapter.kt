package com.airi.assistant.ui.trace

import com.airi.assistant.core.AgentEvent
import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind

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
            .mapIndexed { index, event ->
                ExecutionTraceEvent(
                    executionId = event.executionId,
                    sequence = index.toLong(),
                    timestampMs = event.timestampMs,
                    kind = event.type.toTraceKind(event.errorCode),
                    summary = event.title,
                    detail = event.detail,
                    actionId = event.actionId,
                    durationMs = event.durationMs,
                    errorCode = event.errorCode,
                    sessionId = event.sessionId,
                    machineTag = event.machineTag,
                    parentId = event.parentId,
                    inputParams = event.inputParams,
                )
            }

    private fun AgentEvent.Type.toTraceKind(errorCode: String?): ExecutionTraceKind = when (this) {
        AgentEvent.Type.INTENT_RESOLVED,
        AgentEvent.Type.TOOLS_EXPOSED,
        AgentEvent.Type.PLAN_UPDATED -> ExecutionTraceKind.PLANNING
        AgentEvent.Type.MODEL_TURN,
        AgentEvent.Type.VERIFY -> ExecutionTraceKind.REFLECTING
        AgentEvent.Type.TOOL_CALL,
        AgentEvent.Type.SKILL_LOADED -> ExecutionTraceKind.TOOL_STARTED
        AgentEvent.Type.TOOL_RESULT -> if (errorCode == null) {
            ExecutionTraceKind.TOOL_COMPLETED
        } else {
            ExecutionTraceKind.TOOL_FAILED
        }
        AgentEvent.Type.APPROVAL -> ExecutionTraceKind.WAITING_FOR_APPROVAL
        AgentEvent.Type.FALLBACK -> ExecutionTraceKind.RECOVERING
        AgentEvent.Type.FINAL -> if (errorCode == null) {
            ExecutionTraceKind.COMPLETED
        } else {
            ExecutionTraceKind.FAILED
        }
        AgentEvent.Type.LIFECYCLE -> ExecutionTraceKind.STEP_COMPLETED
    }
}
