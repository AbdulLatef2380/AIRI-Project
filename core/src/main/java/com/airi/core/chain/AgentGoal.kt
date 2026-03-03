package com.airi.core.chain

data class AgentGoal(
    val id: String,
    val description: String,
    val steps: List<PlanStep>
)
