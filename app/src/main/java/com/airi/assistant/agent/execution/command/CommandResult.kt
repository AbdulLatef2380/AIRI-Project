package com.airi.assistant.agent.execution.command

data class CommandResult(
    val success: Boolean,
    val message: String? = null
)
