package com.airi.assistant.brain

data class AgentGoal(
    val id: String,
    val description: String,
    val steps: List<PlanStep>
)
