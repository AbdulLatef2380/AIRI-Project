package com.airi.core.chain

enum class GoalExecutionState {
    PENDING,
    RUNNING,
    VERIFYING,
    SUCCESS,
    FAILED,
    TIMEOUT
}
