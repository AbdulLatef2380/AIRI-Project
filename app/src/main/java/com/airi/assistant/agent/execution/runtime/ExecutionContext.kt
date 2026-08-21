package com.airi.assistant.agent.execution.runtime

import com.airi.core.planning.ActionPlan

data class ExecutionContext(
    val plan: ActionPlan,
    var currentStepIndex: Int = 0,
    val stepHistory: MutableList<StepResult> = mutableListOf(),
    var state: ExecutionState = ExecutionState.IDLE
)
