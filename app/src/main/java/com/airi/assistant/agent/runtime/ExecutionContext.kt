package com.airi.assistant.agent.runtime

import com.airi.assistant.agent.ActionPlan

data class ExecutionContext(
    val plan: ActionPlan,
    var currentStepIndex: Int = 0,
    val stepHistory: MutableList<StepResult> = mutableListOf(),
    var state: ExecutionState = ExecutionState.IDLE
)
