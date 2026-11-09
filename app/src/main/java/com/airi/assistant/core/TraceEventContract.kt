package com.airi.assistant.core

/**
 * The single compatibility contract between the canonical execution trace and
 * the older AgentEvent feed.  UI adapters may project this contract, but must
 * not invent a second lifecycle mapping.
 */
object TraceEventContract {
    fun agentTypeFor(kind: ExecutionTraceKind): AgentEvent.Type = when (kind) {
        ExecutionTraceKind.EXECUTION_STARTED,
        ExecutionTraceKind.PLAN_CREATED,
        ExecutionTraceKind.REASONING_NOTE,
        ExecutionTraceKind.PLANNING -> AgentEvent.Type.PLAN_UPDATED

        ExecutionTraceKind.TOOL_REQUESTED,
        ExecutionTraceKind.TOOL_STARTED -> AgentEvent.Type.TOOL_CALL

        ExecutionTraceKind.TOOL_OUTPUT,
        ExecutionTraceKind.TOOL_COMPLETED,
        ExecutionTraceKind.TOOL_FAILED,
        ExecutionTraceKind.TOOL_CANCELLED -> AgentEvent.Type.TOOL_RESULT

        ExecutionTraceKind.WAITING_FOR_APPROVAL,
        ExecutionTraceKind.APPROVAL_CANCELLED,
        ExecutionTraceKind.APPROVAL_TIMEOUT -> AgentEvent.Type.APPROVAL

        ExecutionTraceKind.RECOVERING -> AgentEvent.Type.FALLBACK
        ExecutionTraceKind.REFLECTING,
        ExecutionTraceKind.VERIFICATION_STARTED,
        ExecutionTraceKind.VERIFICATION_PASSED,
        ExecutionTraceKind.VERIFICATION_FAILED -> AgentEvent.Type.VERIFY

        ExecutionTraceKind.COMPLETED,
        ExecutionTraceKind.FAILED,
        ExecutionTraceKind.CANCELLED,
        ExecutionTraceKind.TIMED_OUT -> AgentEvent.Type.FINAL

        ExecutionTraceKind.STEP_STARTED,
        ExecutionTraceKind.STEP_COMPLETED -> AgentEvent.Type.LIFECYCLE
    }

    fun traceKindFor(type: AgentEvent.Type, errorCode: String?): ExecutionTraceKind = when (type) {
        AgentEvent.Type.INTENT_RESOLVED,
        AgentEvent.Type.TOOLS_EXPOSED,
        AgentEvent.Type.PLAN_UPDATED -> ExecutionTraceKind.PLANNING

        AgentEvent.Type.MODEL_TURN,
        AgentEvent.Type.VERIFY -> ExecutionTraceKind.REFLECTING
        AgentEvent.Type.TOOL_CALL -> ExecutionTraceKind.TOOL_STARTED
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
