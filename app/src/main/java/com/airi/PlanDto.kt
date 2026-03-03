package com.airi.assistant.brain

data class PlanDto(
    val goal_id: String,
    val description: String,
    val steps: List<StepDto>
)

data class StepDto(
    val action: String,
    val text: String? = null
)
