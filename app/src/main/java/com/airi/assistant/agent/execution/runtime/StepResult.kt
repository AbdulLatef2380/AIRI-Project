package com.airi.assistant.agent.execution.runtime

data class StepResult(
    val stepName: String,
    val success: Boolean,
    val message: String? = null
)
