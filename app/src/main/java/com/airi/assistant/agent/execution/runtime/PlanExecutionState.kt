package com.airi.assistant.agent.execution.runtime

enum class PlanExecutionState {
    CREATED,
    READY,
    RUNNING,
    WAITING_DEPENDENCIES,
    COMPLETED,
    FAILED,
    CANCELLED,
    RETRYING,
    BLOCKED
}