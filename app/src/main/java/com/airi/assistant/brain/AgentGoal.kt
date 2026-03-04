package com.airi.assistant.brain

data class AgentGoal(
    val description: String,
    val steps: List<PlanStep>
)
