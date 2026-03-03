package com.airi.core.chain

data class ExecutionResult(
    val state: GoalExecutionState,
    val message: String? = null
)
