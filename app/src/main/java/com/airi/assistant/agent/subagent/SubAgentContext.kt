package com.airi.assistant.agent.subagent

/**
 * Execution context passed to every [SubAgent.canHandle] and [SubAgent.execute] call.
 *
 * Immutable — sub-agents MUST NOT mutate this. A new context is created for
 * each orchestrator task to prevent cross-task state leakage.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PRIVACY LEVELS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   0 = MAXIMUM  — local only; no cloud calls; no private data read
 *   1 = BALANCED — cloud allowed; sanitized data only
 *   2 = STANDARD — cloud allowed; private data with user consent
 */
data class SubAgentContext(

    // ── Identity ────────────────────────────────────────────────────────────

    /** Unique identifier for the current chat session. */
    val sessionId: String,

    /** Firebase UID or "anonymous" for unauthenticated users. */
    val userId: String,

    /**
     * Workspace/project that owns this request. Null is permitted only for
     * legacy or quick-chat work that has not entered a project yet.
     */
    val projectId: String? = null,

    // ── Conversation context ─────────────────────────────────────────────────

    /**
     * Recent conversation turns in [role: content] format.
     * Typically the last 6–10 turns. Agents use this for continuity.
     */
    val recentTurns: List<String> = emptyList(),

    /**
     * Resolved world state key-value pairs (battery, network, locale, etc.)
     * as captured by [WorldStateManager] at dispatch time.
     */
    val worldState: Map<String, String> = emptyMap(),

    // ── Permissions & privacy ─────────────────────────────────────────────────

    /**
     * Android runtime permissions that are currently GRANTED.
     * Agents check this before accessing Calendar, Contacts, etc.
     */
    val grantedPermissions: List<String> = emptyList(),

    /**
     * Tool IDs that are available for use in this context.
     * Sub-agents MUST NOT invoke tools outside this allowlist.
     */
    val allowedTools: List<String> = emptyList(),

    /**
     * Privacy level: 0=MAXIMUM, 1=BALANCED, 2=STANDARD.
     * Cloud-requiring agents are blocked at level 0.
     * Private data access requires explicit consent at level 0–1.
     */
    val privacyLevel: Int = 1,

    // ── Resource budget ──────────────────────────────────────────────────────

    /**
     * Remaining cloud token budget for this session.
     * Agents estimate their cost via [SubAgentCapability.costTier] and
     * decline if budget is insufficient.
     */
    val remainingCloudTokenBudget: Int = 50_000,

    /**
     * Maximum wall-clock time this task is allowed to run (ms).
     * Orchestrator cancels the task coroutine at this deadline.
     * -1 = no timeout (durable background tasks).
     */
    val timeoutMs: Long = 30_000L,

    // ── Orchestration metadata ─────────────────────────────────────────────────

    /**
     * Unique ID of the parent orchestration task.
     * Used for trace correlation and checkpoint persistence.
     */
    val parentTaskId: String = "",

    /**
     * Nesting depth (0 = top-level user request, 1 = sub-task, …).
     * Prevents infinite delegation chains. Max depth = 3.
     */
    val nestingDepth: Int = 0,

    /**
     * Resolved outputs from previously completed dependency tasks.
     * Key = taskId of the dependency; Value = its result text.
     * Agents can reference these to chain results.
     */
    val dependencyResults: Map<String, String> = emptyMap()
) {
    companion object {
        const val MAX_NESTING_DEPTH = 3
        const val PRIVACY_MAXIMUM   = 0
        const val PRIVACY_BALANCED  = 1
        const val PRIVACY_STANDARD  = 2

        /** Minimal context for quick unit tests. */
        fun test(sessionId: String = "test", userId: String = "test") =
            SubAgentContext(sessionId = sessionId, userId = userId)
    }

    /** Whether cloud calls are permitted in this context. */
    val cloudAllowed: Boolean get() = privacyLevel > PRIVACY_MAXIMUM

    /** Whether private data access is permitted in this context. */
    val privateDataAllowed: Boolean get() = privacyLevel >= PRIVACY_STANDARD

    /** Whether a child agent can be dispatched (depth not exceeded). */
    val canDelegate: Boolean get() = nestingDepth < MAX_NESTING_DEPTH
}
