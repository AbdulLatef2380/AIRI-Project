package com.airi.assistant.brain

// ==============================
// 🧠 Brain Input / Output
// ==============================

data class BrainInput(
    val text: String,
    val screenContext: String
)

data class BrainOutput(
    val message: String,
    val goal: AgentGoal? = null
)

// ==============================
// 🎯 Agent Goal
// ==============================

data class AgentGoal(
    val description: String,
    val steps: List<PlanStep>
)

// ==============================
// 📋 Plan Steps
// ==============================

sealed class PlanStep {

    data class Click(val target: String) : PlanStep()

    object Scroll : PlanStep()

    data class Wait(val millis: Long) : PlanStep()
}
