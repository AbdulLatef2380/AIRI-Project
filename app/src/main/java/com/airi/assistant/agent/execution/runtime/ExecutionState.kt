package com.airi.assistant.agent.execution.runtime

enum class ExecutionState {
    IDLE,
    PLANNING,
    EXECUTING,
    WAITING_CONFIRMATION,
    ROLLING_BACK,
    COMPLETED,
    FAILED
}
