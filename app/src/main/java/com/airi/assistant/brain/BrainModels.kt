package com.airi.assistant.brain

data class BrainInput(
    val text: String
)

data class BrainOutput(
    val message: String,
    val goal: AgentGoal? = null
)

data class AgentGoal(
    val id: String,
    val description: String,
    val steps: List<PlanStep>
)

sealed class PlanStep {
    data class Click(val target: String) : PlanStep()
    object Scroll : PlanStep()
    data class Wait(val target: String) : PlanStep()
}
