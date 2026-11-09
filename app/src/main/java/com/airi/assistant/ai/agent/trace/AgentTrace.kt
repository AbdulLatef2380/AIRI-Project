package com.airi.assistant.ai.agent.trace

import java.util.UUID

data class AgentTrace(
    val id: String = UUID.randomUUID().toString(),
    /** Stable log identity. Legacy traces use [id] as their trace identity. */
    val traceId: String = id,
    val originalInput: String,
    val steps: List<AgentStep> = emptyList(),
    val finalResult: String = "",
    val success: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /** Chat execution that produced this trace; blank for legacy skill dispatch. */
    val executionId: String = "",
    /** Persisted chat message that carries the answer; null when not tied to one. */
    val messageId: Long? = null,
    /** Chat session that owns the execution; blank when unknown. */
    val sessionId: String = "",
    /** [SOURCE_CHAT] or [SOURCE_SKILL]. */
    val source: String = SOURCE_SKILL,
    /** Model selected at dispatch; a runtime fallback may have served another model. */
    val model: String? = null,
    /** Provider selected at dispatch; a runtime fallback may have served another provider. */
    val provider: String? = null,
    /** Model/provider requested before routing or fallback. */
    val requestedModel: String? = null,
    val requestedProvider: String? = null,
    /** Model/provider that actually served the response. */
    val resolvedModel: String? = null,
    val resolvedProvider: String? = null,
    /** Redacted reason for changing the requested target, when applicable. */
    val fallbackReason: String? = null,
    /** Approximate visible-output token count; this is not provider-reported usage. */
    val estimatedOutputTokens: Int? = null,
    /** Measured from request dispatch to the first non-blank visible token chunk. */
    val firstVisibleTokenLatencyMs: Long? = null,
    /** True when steps beyond [AgentTraceManager.MAX_STEPS] were dropped. */
    val truncated: Boolean = false,
    /** Number of steps dropped because of the cap. */
    val droppedSteps: Int = 0,
    /**
     * Position in [steps] where the dropped steps used to be: 0 when the oldest steps were dropped
     * ([AgentTraceManager.addStep]), 1 when the first step was kept and the middle was dropped
     * ([ChatExecutionTraceMapper]). Lets the log show a gap marker where the missing steps were.
     */
    val droppedAtIndex: Int = 0,
    /**
     * True while the execution is still running. Such a trace is a live snapshot built by
     * [LiveTraceBridge]: it is shown in the log but never written to disk, and it is replaced by
     * the final record (same [id]) when the run ends.
     */
    val inProgress: Boolean = false,
) {
    /** Explicit correlation tuple used when navigating from a log to chat. */
    val correlationKey: String
        get() = "traceId=$traceId|executionId=$executionId|messageId=${messageId?.toString().orEmpty()}|sessionId=$sessionId"

    // Model turns are bookkeeping about the model, not units of work: they never change the step
    // counters or the error state that the run card, logs and notifications are built on.
    val stepCount: Int get() = steps.count { it.type != AgentStepType.MODEL_TURN }
    val successCount: Int get() = steps.count {
        it.type != AgentStepType.MODEL_TURN &&
            it.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.COMPLETED &&
            it.success
    }
    val hasErrors: Boolean get() = steps.any {
        it.type != AgentStepType.MODEL_TURN &&
            (it.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.FAILED ||
                it.traceStatus == com.airi.assistant.core.ExecutionTraceStatus.CANCELLED ||
                it.error != null)
    }

    companion object {
        const val SOURCE_SKILL = "skill"
        const val SOURCE_CHAT = "chat"
    }
}
