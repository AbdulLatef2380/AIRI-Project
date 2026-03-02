package com.airi.assistant.agent.runtime

data class StepResult(
    val stepName: String,
    val success: Boolean,
    val message: String? = null
)
