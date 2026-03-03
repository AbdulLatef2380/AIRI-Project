package com.airi.assistant.brain

data class AgentGoal(
    val id: String,
    val description: String,
    val action: String
)

data class BrainOutput(
    val message: String,
    val goalId: String? = null
)

data class PlanDto(
    val goals: List<AgentGoal>
)
