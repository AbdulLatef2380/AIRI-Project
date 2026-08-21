package com.airi.core.planning

data class AgentGoal(
    val id: String,
    val description: String,
    val steps: List<PlanStep>
)
