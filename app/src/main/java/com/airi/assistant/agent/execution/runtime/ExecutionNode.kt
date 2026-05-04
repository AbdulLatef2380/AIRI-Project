package com.airi.assistant.agent.execution.runtime

data class ExecutionNode(
    val nodeId: String,
    val dependencies: List<String> = emptyList(),
    val assignedAgent: String? = null,
    val executionState: PlanExecutionState = PlanExecutionState.CREATED,
    val retryCount: Int = 0,
    val timeoutMs: Long = 30_000L,
    val producedArtifacts: List<ExecutionArtifact> = emptyList(),
    val structuredOutputs: Map<String, String> = emptyMap()
)