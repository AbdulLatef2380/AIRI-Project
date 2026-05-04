package com.airi.assistant.agent.execution.runtime

data class ExecutionGraphSnapshot(
    val planId: String,
    val planIntent: String,
    val nodes: List<ExecutionNode>,
    val completedNodeIds: List<String> = emptyList(),
    val failedNodeIds: List<String> = emptyList(),
    val activeNodeId: String? = null,
    val executionState: PlanExecutionState = PlanExecutionState.CREATED,
    val reflectionNotes: List<String> = emptyList(),
    val startedAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis()
) {
    /** IDs of nodes not yet completed or failed. */
    val pendingNodeIds: List<String> get() =
        nodes.map { it.nodeId } - completedNodeIds.toSet() - failedNodeIds.toSet()
}
