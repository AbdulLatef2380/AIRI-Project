package com.airi.assistant.agent.trace

data class TraceNodeState(
    val id: String,
    val step: TraceStep,
    val executionState: NodeExecutionState = NodeExecutionState.RUNNING
)

enum class NodeExecutionState { RUNNING, DONE, FAILED }
