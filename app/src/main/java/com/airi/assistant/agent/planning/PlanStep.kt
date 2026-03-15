package com.airi.assistant.agent.planning

sealed class PlanStep {

    data class Click(val text: String) : PlanStep()

    object Scroll : PlanStep()

    data class Wait(val millis: Long) : PlanStep()
}
