package com.airi.assistant.agent

data class ActionPlan(
    val intent: String,
    val confidence: Double,
    val steps: List<String>,
    val requiresConfirmation: Boolean
)
