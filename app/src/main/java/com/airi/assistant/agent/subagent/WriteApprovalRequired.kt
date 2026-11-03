package com.airi.assistant.agent.subagent

/**
 * V2 — implemented by sub-agents whose requests can CHANGE or DELETE user data.
 *
 * The chat path blocks such work unless a task-owned approval exists
 * (AgentLoopSideEffectPolicy). Callers that reach a sub-agent directly — the voice path —
 * use this contract to decide, per request, whether explicit user approval is needed first.
 */
interface WriteApprovalRequired {
    /** True when [input] would change or delete user data (and therefore needs approval). */
    fun requiresWriteApproval(input: String): Boolean

    /** Short, human-readable description shown on the confirmation screen. */
    fun describeWrite(input: String): String =
        "AIRI wants to save a change on your device: \"${input.trim().take(120)}\""
}
