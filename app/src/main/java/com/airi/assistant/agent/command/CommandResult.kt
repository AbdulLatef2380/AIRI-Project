package com.airi.assistant.agent.command

data class CommandResult(
    val success: Boolean,
    val message: String? = null
)
