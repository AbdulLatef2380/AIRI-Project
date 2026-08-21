package com.airi.core.planning

data class ActionPlan(
    val intent: String,
    val confidence: Double,
    val steps: List<PlanStep>,
    val requiresConfirmation: Boolean = false
)
