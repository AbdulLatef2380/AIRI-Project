package com.airi.assistant.ai.agent

data class TaskStep(
    val toolName: String,
    val params: Map<String, String>,
    val description: String = ""
)
