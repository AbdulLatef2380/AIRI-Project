package com.airi.assistant.agent.loop.tool

/**
 * Fail-closed admission for AgentLoop tools that can change a user resource or
 * live device state. A chat loop has no durable task/run/step context today,
 * therefore it must not invoke these adapters directly and then mistake an
 * in-memory confirmation string for a replay-safe approval.
 */
internal object AgentLoopSideEffectPolicy {

    enum class Decision {
        ALLOW_READ,
        DURABLE_CONTEXT_REQUIRED
    }

    private val sideEffectingTools = setOf(
        "calendar_create",
        "set_alarm",
        "create_note",
        "open_app",
        "tap",
        "type_text",
        "scroll_down",
        "go_back"
    )

    fun decide(toolName: String, hasDurableExecutionContext: Boolean): Decision = when {
        toolName !in sideEffectingTools -> Decision.ALLOW_READ
        hasDurableExecutionContext -> Decision.ALLOW_READ
        else -> Decision.DURABLE_CONTEXT_REQUIRED
    }

    fun blockedMessage(toolName: String): String = when (toolName) {
        "calendar_create" -> "Calendar creation requires a task-owned approval session before it can run."
        "create_note" -> "Creating a note requires a task-owned approval session before it can run."
        "set_alarm" -> "Setting an alarm requires a task-owned approval session before it can run."
        else -> "This device action requires a task-owned approval session before it can run."
    }
}
