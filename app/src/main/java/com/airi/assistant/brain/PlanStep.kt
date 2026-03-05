package com.airi.assistant.brain

sealed class PlanStep {

    data class Click(val text: String) : PlanStep()

    object Scroll : PlanStep()

    data class Wait(val millis: Long) : PlanStep()
}
