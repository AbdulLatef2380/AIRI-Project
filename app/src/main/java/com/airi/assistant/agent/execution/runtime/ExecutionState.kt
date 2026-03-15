package com.airi.assistant.agent.execution

enum class ExecutionState {
    IDLE,
    PLANNING,
    EXECUTING,
    WAITING_CONFIRMATION,
    ROLLING_BACK,
    COMPLETED,
    FAILED
}
