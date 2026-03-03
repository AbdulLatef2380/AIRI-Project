package com.airi.assistant.brain

data class StepDto(
    val id: String,
    val action: String
)

data class PlanDto(
    val steps: List<StepDto>
)

data class BrainInput(
    val text: String,
    val source: InputSource,
    val withContext: Boolean = false
)

enum class InputSource {
    USER,
    SYSTEM,
    CHAT
}

data class BrainOutput(
    val message: String,
    val goalId: String? = null
)
