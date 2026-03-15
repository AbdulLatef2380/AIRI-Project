package com.airi.assistant.agent.execution

data class StepResult(
    val stepName: String,
    val success: Boolean,
    val message: String? = null
)
