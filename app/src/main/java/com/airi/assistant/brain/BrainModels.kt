package com.airi.assistant.brain

import com.airi.core.chain.AgentGoal

data class BrainInput(
    val text: String,
    val withContext: Boolean = false
)

data class BrainOutput(
    val message: String,
    val goal: AgentGoal? = null
)

data class PlanDto(
    val steps: List<StepDto>
)

data class StepDto(
    val id: String,
    val action: String
)
