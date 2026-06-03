package com.airi.assistant.agent.planning

/**
 * Minimal planning types — GoalNode and GraphSnapshot.
 *
 * These were extracted from TypedPlanGraph.kt when that file was deleted
 * (the graph execution engine was removed in the agent-first migration).
 * GoalNode is retained because ProductionAgentOrchestrator uses it to build
 * GraphSnapshot for AgentObservabilityHub UI display during scheduled-task
 * execution.
 */

enum class NodeStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

sealed class RecoveryBranch {
    data class Retry(val maxAttempts: Int) : RecoveryBranch()
    object Skip : RecoveryBranch()
    object Abort : RecoveryBranch()
}

/**
 * A single node in a multi-step execution plan.
 * Used by [com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator]
 * for observability snapshot construction.
 */
data class GoalNode(
    val id:             String,
    val description:    String,
    val action:         String,
    val params:         Map<String, String> = emptyMap(),
    val dependsOn:      List<String> = emptyList(),
    val recoveryBranch: RecoveryBranch = RecoveryBranch.Retry(1),
    val isCritical:     Boolean = false
) {
    @Volatile var status: NodeStatus = NodeStatus.PENDING
}

/**
 * Snapshot of a running/completed multi-task graph.
 * Written by [com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator],
 * read by [com.airi.assistant.agent.observability.AgentObservabilityHub] and
 * displayed in [com.airi.assistant.ui.screens.ObservabilityScreen].
 */
data class GraphSnapshot(
    val goalId:       String,
    val description:  String,
    val totalNodes:   Int,
    val doneNodes:    Int,
    val failedNodes:  Int,
    val skippedNodes: Int,
    val nodes:        List<GoalNode>
)
