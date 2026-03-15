package com.airi.assistant.agent.execution

data class ExecutionResult(
    val success: Boolean,
    val feedback: String,
    val timestamp: Long = System.currentTimeMillis()
)
