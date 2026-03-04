package com.airi.assistant.brain

sealed class PlanStep {

    data class Click(val target: String) : PlanStep()

    object Scroll : PlanStep()

    data class Wait(val target: String) : PlanStep()
}
