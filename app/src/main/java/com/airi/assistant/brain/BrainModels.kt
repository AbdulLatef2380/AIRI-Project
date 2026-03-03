package com.airi.assistant.brain

data class AgentGoal(
    val id: String,
    val description: String,
    val action: String
)

data class StepDto(
    val id: String,
    val action: String
)

data class PlanDto(
    val steps: List<StepDto>
)

data class BrainInput(
    val text: String,
    val source: InputSource
)

enum class InputSource {
    USER,
    SYSTEM
}

data class BrainOutput(
    val message: String,
    val goalId: String? = null
)
