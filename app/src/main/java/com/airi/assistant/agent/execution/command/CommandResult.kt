package com.airi.assistant.agent.execution.command

data class CommandResult(
    val success: Boolean,
    val message: String? = null,
    /** True when the action was refused ONLY because it needs explicit user approval. */
    val needsConfirmation: Boolean = false,
    /** Human-readable label of the UI element that triggered the confirmation requirement. */
    val confirmationLabel: String? = null
)
