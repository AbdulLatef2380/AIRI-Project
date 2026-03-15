package com.airi.assistant.agent.execution

data class CommandResult(
    val success: Boolean,
    val message: String? = null
)
