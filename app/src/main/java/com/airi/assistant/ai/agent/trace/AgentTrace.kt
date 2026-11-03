package com.airi.assistant.ai.agent.trace

import java.util.UUID

data class AgentTrace(
    val id: String = UUID.randomUUID().toString(),
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
    /** Approximate visible-output token count; this is not provider-reported usage. */
    val estimatedOutputTokens: Int? = null,
    /** Measured from request dispatch to the first non-blank visible token chunk. */
    val firstVisibleTokenLatencyMs: Long? = null,
    /** True when steps beyond [AgentTraceManager.MAX_STEPS] were dropped. */
    val truncated: Boolean = false,
    /** Number of steps dropped because of the cap. */
    val droppedSteps: Int = 0,
) {
    val stepCount: Int get() = steps.size
    val successCount: Int get() = steps.count { it.success }
    val hasErrors: Boolean get() = steps.any { !it.success || it.error != null }

    companion object {
        const val SOURCE_SKILL = "skill"
        const val SOURCE_CHAT = "chat"
    }
}
