package com.airi.core.chain

sealed class PlanStep {
    data class Click(val text: String) : PlanStep()
    data class ScrollForward(val amount: Int = 1) : PlanStep()
    data class WaitFor(val text: String, val timeout: Long = 2000) : PlanStep()
}
