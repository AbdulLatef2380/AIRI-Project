package com.airi.assistant.agent.execution.runtime

data class ExecutionGraphResult(
    val planId: String,
    val finalText: String,
    val snapshot: ExecutionGraphSnapshot,
    val nodeResults: Map<String, ExecutionNodeResult>
)

sealed class ExecutionNodeResult {
    abstract val nodeId: String

    data class Success(
        override val nodeId: String,
        val finalText: String,
        val structuredOutputs: Map<String, String> = emptyMap()
    ) : ExecutionNodeResult() {
        val summary: String get() = finalText.take(2000)
    }

    data class Failure(
        override val nodeId: String,
        val reason: String
    ) : ExecutionNodeResult() {
        val summary: String get() = "FAILED: $reason"
    }
}

sealed class ExecutionGraphEvent {
    data class PlanStarted(val intent: String, val totalNodes: Int) : ExecutionGraphEvent()
    data class WaveStarted(val nodeIds: List<String>) : ExecutionGraphEvent()
    data class NodeStarted(val nodeId: String, val agentId: String?, val dependencies: List<String>) : ExecutionGraphEvent()
    data class NodeProgress(val nodeId: String, val percent: Int, val message: String) : ExecutionGraphEvent()
    data class NodeArtifact(val nodeId: String, val artifact: ExecutionArtifact) : ExecutionGraphEvent()
    data class ToolObserved(val nodeId: String, val toolName: String) : ExecutionGraphEvent()
    data class NodeCompleted(val nodeId: String, val finalText: String) : ExecutionGraphEvent()
    data class NodeFailed(val nodeId: String, val reason: String) : ExecutionGraphEvent()
    data class Reflection(val message: String) : ExecutionGraphEvent()
    data class GraphSnapshot(val snapshot: ExecutionGraphSnapshot) : ExecutionGraphEvent()
    data class PlanCompleted(val finalText: String, val snapshot: ExecutionGraphSnapshot) : ExecutionGraphEvent()
}

interface ExecutionSnapshotStore {
    fun save(snapshot: ExecutionGraphSnapshot)
    fun load(planId: String): ExecutionGraphSnapshot?
}
