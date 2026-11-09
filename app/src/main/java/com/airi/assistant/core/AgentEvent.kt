package com.airi.assistant.core

import com.airi.assistant.ai.agent.trace.ModelTurnRecord
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Canonical, redacted event contract for the agent timeline.
 * All projections (chat card, detail screen, activity summary and export) can consume this model.
 */
data class AgentEvent(
    val eventId: String,
    val executionId: String,
    val sessionId: String,
    val sequence: Long,
    val timestampMs: Long,
    val type: Type,
    val title: String,
    val detail: String? = null,
    val actionId: String? = null,
    val parentId: String? = null,
    val durationMs: Long? = null,
    val errorCode: String? = null,
    val machineTag: String? = null,
    val inputParams: Map<String, String> = emptyMap(),
) {
    enum class Type {
        INTENT_RESOLVED,
        TOOLS_EXPOSED,
        MODEL_TURN,
        TOOL_CALL,
        TOOL_RESULT,
        SKILL_LOADED,
        PLAN_UPDATED,
        VERIFY,
        APPROVAL,
        FALLBACK,
        FINAL,
        LIFECYCLE,
    }

    companion object {
        fun fromTrace(event: ExecutionTraceEvent): AgentEvent = AgentEvent(
            eventId = event.eventId,
            executionId = event.executionId,
            sessionId = event.sessionId,
            sequence = event.sequence,
            timestampMs = event.timestampMs,
            type = when (event.kind) {
                ExecutionTraceKind.PLANNING -> Type.PLAN_UPDATED
                ExecutionTraceKind.TOOL_STARTED -> Type.TOOL_CALL
                ExecutionTraceKind.TOOL_COMPLETED,
                ExecutionTraceKind.TOOL_FAILED,
                ExecutionTraceKind.TOOL_CANCELLED -> Type.TOOL_RESULT
                ExecutionTraceKind.WAITING_FOR_APPROVAL,
                ExecutionTraceKind.APPROVAL_CANCELLED,
                ExecutionTraceKind.APPROVAL_TIMEOUT -> Type.APPROVAL
                ExecutionTraceKind.RECOVERING -> Type.FALLBACK
                ExecutionTraceKind.REFLECTING -> Type.VERIFY
                ExecutionTraceKind.COMPLETED,
                ExecutionTraceKind.FAILED,
                ExecutionTraceKind.CANCELLED,
                ExecutionTraceKind.TIMED_OUT -> Type.FINAL
                ExecutionTraceKind.STEP_STARTED,
                ExecutionTraceKind.STEP_COMPLETED -> Type.LIFECYCLE
            },
            title = event.summary,
            detail = event.detail,
            actionId = event.actionId,
            parentId = event.parentId,
            durationMs = event.durationMs,
            errorCode = event.errorCode,
            machineTag = event.machineTag,
            inputParams = event.inputParams,
        )

        fun modelTurn(executionId: String, sessionId: String, record: ModelTurnRecord): AgentEvent = AgentEvent(
            eventId = "$executionId:model-turn:${record.turn}",
            executionId = executionId,
            sessionId = sessionId,
            sequence = record.turn.toLong(),
            timestampMs = record.timestampMs,
            type = Type.MODEL_TURN,
            title = "Model turn ${record.turn}",
            detail = "${record.providerId}/${record.modelId}; finish=${record.finishReason}",
            durationMs = record.durationMs,
        )
    }
}

/** Bounded live delivery channel. Durable history remains in ExecutionStatusBus/AgentRunStore. */
object AgentEventBus {
    private const val MAX_EVENTS = 512
    private val _events = MutableSharedFlow<AgentEvent>(
        replay = 0,
        extraBufferCapacity = MAX_EVENTS,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()
    private val _recent = MutableStateFlow<List<AgentEvent>>(emptyList())
    /** Bounded live snapshot for detail screens that subscribe after execution has started. */
    val recent: StateFlow<List<AgentEvent>> = _recent.asStateFlow()

    fun emit(event: AgentEvent) {
        _recent.update { current ->
            (current + event).takeLast(MAX_EVENTS)
        }
        _events.tryEmit(event)
    }

    fun snapshot(executionId: String): List<AgentEvent> =
        if (executionId.isBlank()) emptyList() else recent.value.filter { it.executionId == executionId }

    fun clear() {
        _recent.value = emptyList()
    }
}
