package com.airi.assistant.agent.execution.runtime

import android.util.Log
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.PlanStep
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class ExecutionGraphRuntime(
    private val orchestrator: ProductionAgentOrchestrator,
    private val durableTaskManager: DurableTaskManager? = null,
    private val snapshotStore: ExecutionSnapshotStore? = null
) {
    private val mutex = Mutex()
    private val activeSnapshots = ConcurrentHashMap<String, ExecutionGraphSnapshot>()
    private val tag = "ExecutionGraphRuntime"

    fun currentSnapshot(planId: String): ExecutionGraphSnapshot? = activeSnapshots[planId]

    /** Returns all currently active (non-terminal) plan snapshots. */
    fun allActiveSnapshots(): List<ExecutionGraphSnapshot> =
        activeSnapshots.values.filter {
            it.executionState == PlanExecutionState.RUNNING ||
            it.executionState == PlanExecutionState.RETRYING
        }

    fun restore(snapshot: ExecutionGraphSnapshot) {
        activeSnapshots[snapshot.planId] = snapshot
        snapshotStore?.save(snapshot)
    }

    fun cancel(planId: String) {
        val current = activeSnapshots[planId] ?: return
        val cancelled = current.copy(executionState = PlanExecutionState.CANCELLED)
        activeSnapshots[planId] = cancelled
        snapshotStore?.save(cancelled)
    }

    /**
     * Cold Flow API — collect to drive the execution; each downstream
     * event is emitted as the graph advances.
     */
    fun resume(plan: ActionPlan, context: SubAgentContext): Flow<ExecutionGraphEvent> = flow {
        mutex.withLock {
            val runtimePlan = buildRuntimePlan(plan)
            snapshotStore?.load(runtimePlan.planId)?.let { restore(it) }
            emit(ExecutionGraphEvent.PlanStarted(plan.intent, plan.steps.size))
            val result = executeGraph(runtimePlan, context) { emit(it) }
            emit(ExecutionGraphEvent.PlanCompleted(result.finalText, result.snapshot))
        }
    }

    /**
     * Callback API — useful when the caller already owns a coroutine scope
     * and wants structured concurrency without Flow overhead.
     */
    suspend fun execute(
        plan: ActionPlan,
        context: SubAgentContext,
        emitEvent: suspend (ExecutionGraphEvent) -> Unit
    ): ExecutionGraphResult = mutex.withLock {
        val runtimePlan = buildRuntimePlan(plan)
        snapshotStore?.load(runtimePlan.planId)?.let { restore(it) }
        executeGraph(runtimePlan, context, emitEvent)
    }

    // ── Core graph execution ────────────────────────────────────────────────

    private suspend fun executeGraph(
        runtimePlan: RuntimePlan,
        context: SubAgentContext,
        emitEvent: suspend (ExecutionGraphEvent) -> Unit
    ): ExecutionGraphResult {
        val initial = activeSnapshots[runtimePlan.planId] ?: ExecutionGraphSnapshot(
            planId = runtimePlan.planId,
            planIntent = runtimePlan.intent,
            nodes = runtimePlan.nodes.values.map { it.toExecutionNode() }
        )
        val running = initial.copy(executionState = PlanExecutionState.RUNNING)
        activeSnapshots[runtimePlan.planId] = running
        snapshotStore?.save(running)
        emitEvent(ExecutionGraphEvent.GraphSnapshot(running))

        val nodeResults = linkedMapOf<String, ExecutionNodeResult>()
        val completed = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        var lastText = ""

        while (completed.size + failed.size < runtimePlan.nodes.size) {
            // Compute the next wave: nodes whose deps are all completed
            val ready = runtimePlan.nodes.values
                .filter { it.id !in completed && it.id !in failed }
                .filter { node -> node.dependencies.all { it in completed } }

            if (ready.isEmpty()) {
                // Remaining nodes are permanently blocked by failed deps
                val blocked = runtimePlan.nodes.values.count { it.id !in completed && it.id !in failed }
                if (blocked > 0) {
                    emitEvent(ExecutionGraphEvent.Reflection(
                        "$blocked node(s) permanently blocked by upstream failures; halting."
                    ))
                }
                break
            }

            emitEvent(ExecutionGraphEvent.WaveStarted(ready.map { it.id }))

            // Execute the wave in parallel, preserving node identity for error attribution
            coroutineScope {
                val nodeDeferreds = ready.map { node ->
                    node to async { runNode(node, context, nodeResults, emitEvent) }
                }
                for ((node, deferred) in nodeDeferreds) {
                    val result: ExecutionNodeResult = try {
                        deferred.await()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(tag, "Unexpected failure in node ${node.id}", e)
                        ExecutionNodeResult.Failure(node.id, "Runtime error: ${e.message ?: "unknown"}")
                    }
                    when (result) {
                        is ExecutionNodeResult.Success -> {
                            completed += result.nodeId
                            nodeResults[result.nodeId] = result
                            if (result.finalText.isNotBlank()) lastText = result.finalText
                        }
                        is ExecutionNodeResult.Failure -> {
                            failed += result.nodeId
                            nodeResults[result.nodeId] = result
                        }
                    }
                    updateSnapshot(runtimePlan.planId, completed, failed, nodeResults, result.nodeId, emitEvent)
                }
            }
        }

        val finalState = if (failed.isEmpty()) PlanExecutionState.COMPLETED else PlanExecutionState.FAILED
        val finalSnapshot = (activeSnapshots[runtimePlan.planId] ?: running).copy(
            executionState = finalState,
            completedNodeIds = completed.toList(),
            failedNodeIds = failed.toList(),
            updatedAtMs = System.currentTimeMillis()
        )
        activeSnapshots[runtimePlan.planId] = finalSnapshot
        snapshotStore?.save(finalSnapshot)
        emitEvent(ExecutionGraphEvent.GraphSnapshot(finalSnapshot))

        return ExecutionGraphResult(
            planId = runtimePlan.planId,
            finalText = lastText.ifBlank { runtimePlan.intent },
            snapshot = finalSnapshot,
            nodeResults = nodeResults
        )
    }

    // ── Single-node execution ────────────────────────────────────────────────

    private suspend fun runNode(
        node: RuntimeNode,
        context: SubAgentContext,
        previousResults: Map<String, ExecutionNodeResult>,
        emitEvent: suspend (ExecutionGraphEvent) -> Unit
    ): ExecutionNodeResult {
        val nodeContext = context.copy(
            dependencyResults = previousResults.mapValues { (it.value as? ExecutionNodeResult.Success)?.finalText ?: "" },
            nestingDepth = context.nestingDepth
        )
        emitEvent(ExecutionGraphEvent.NodeStarted(node.id, node.agentId, node.dependencies))
        val timeout = node.timeoutMs.takeIf { it > 0 } ?: 30_000L

        val result = withTimeoutOrNull(timeout) {
            orchestrator.executeSingle(node.input, nodeContext) { event ->
                when (event) {
                    is com.airi.assistant.agent.subagent.AgentEvent.ToolCall ->
                        emitEvent(ExecutionGraphEvent.ToolObserved(node.id, event.toolName))
                    is com.airi.assistant.agent.subagent.AgentEvent.Progress ->
                        emitEvent(ExecutionGraphEvent.NodeProgress(node.id, event.percentComplete, event.message))
                    is com.airi.assistant.agent.subagent.AgentEvent.PartialResult ->
                        emitEvent(ExecutionGraphEvent.NodeArtifact(
                            node.id,
                            ExecutionArtifact("partial", event.text, node.id)
                        ))
                    else -> Unit
                }
            }
        }

        return when (result) {
            is ProductionAgentOrchestrator.ExecutionResult.Success -> {
                val structured = buildStructuredOutputs(node, result.finalResult, result.taskResults)
                emitEvent(ExecutionGraphEvent.NodeCompleted(node.id, result.finalResult))
                ExecutionNodeResult.Success(node.id, result.finalResult, structured)
            }
            is ProductionAgentOrchestrator.ExecutionResult.PartialFailure -> {
                val reason = result.taskErrors.values.firstOrNull() ?: "Unknown partial failure"
                emitEvent(ExecutionGraphEvent.NodeFailed(node.id, reason))
                ExecutionNodeResult.Failure(node.id, reason)
            }
            null -> {
                val msg = "Timed out after ${timeout}ms"
                emitEvent(ExecutionGraphEvent.NodeFailed(node.id, msg))
                ExecutionNodeResult.Failure(node.id, msg)
            }
        }
    }

    // ── Snapshot helpers ─────────────────────────────────────────────────────

    private suspend fun updateSnapshot(
        planId: String,
        completed: Set<String>,
        failed: Set<String>,
        nodeResults: Map<String, ExecutionNodeResult>,
        activeNodeId: String,
        emitEvent: suspend (ExecutionGraphEvent) -> Unit
    ) {
        val existing = activeSnapshots[planId] ?: return
        val updatedNodes = existing.nodes.map { node ->
            when (val result = nodeResults[node.nodeId]) {
                is ExecutionNodeResult.Success -> node.copy(
                    executionState = PlanExecutionState.COMPLETED,
                    producedArtifacts = listOf(ExecutionArtifact("result", result.finalText, node.nodeId)),
                    structuredOutputs = result.structuredOutputs
                )
                is ExecutionNodeResult.Failure -> node.copy(
                    executionState = PlanExecutionState.FAILED
                )
                null -> node.copy(
                    executionState = if (node.dependencies.all { it in completed })
                        PlanExecutionState.READY
                    else
                        PlanExecutionState.WAITING_DEPENDENCIES
                )
            }
        }
        val snapshot = existing.copy(
            nodes = updatedNodes,
            completedNodeIds = completed.toList(),
            failedNodeIds = failed.toList(),
            activeNodeId = activeNodeId,
            executionState = if (failed.isEmpty()) PlanExecutionState.RUNNING else PlanExecutionState.RETRYING,
            updatedAtMs = System.currentTimeMillis()
        )
        activeSnapshots[planId] = snapshot
        snapshotStore?.save(snapshot)
        emitEvent(ExecutionGraphEvent.GraphSnapshot(snapshot))
    }

    // ── Plan → RuntimePlan builder ────────────────────────────────────────────

    private fun buildRuntimePlan(plan: ActionPlan): RuntimePlan {
        val nodes = plan.steps.map { step ->
            RuntimeNode(
                id = step.id,
                agentId = inferAgent(step),
                dependencies = step.dependsOn,
                timeoutMs = when (step) {
                    is PlanStep.Wait -> step.durationMs ?: 1_000L
                    else -> 30_000L
                },
                input = describe(step)
            )
        }.associateBy { it.id }
        return RuntimePlan(UUID.randomUUID().toString(), plan.intent, nodes)
    }

    private fun inferAgent(step: PlanStep): String? = when (step) {
        is PlanStep.Search -> "research"
        is PlanStep.OpenApp, is PlanStep.Click, is PlanStep.Type,
        is PlanStep.Navigate, is PlanStep.Scroll -> "android"
        is PlanStep.Wait -> "productivity"
        is PlanStep.Custom -> when (step.action.lowercase()) {
            "search", "research" -> "research"
            "note", "notes", "create_note" -> "productivity"
            "memory", "recall" -> "memory"
            "code", "implement" -> "coding"
            else -> null
        }
    }

    private fun describe(step: PlanStep): String = when (step) {
        is PlanStep.Search -> step.query
        is PlanStep.OpenApp -> "Open ${step.appName}"
        is PlanStep.Click -> "Click ${step.targetText}"
        is PlanStep.Type -> step.text
        is PlanStep.Navigate -> "Navigate ${step.direction}"
        is PlanStep.Wait -> "Wait ${step.durationMs ?: 1_000L}ms"
        is PlanStep.Scroll -> "Scroll ${step.direction}"
        is PlanStep.Custom -> step.parameters.entries
            .joinToString(" ") { "${it.key}=${it.value}" }
            .ifBlank { step.action }
    }

    private fun buildStructuredOutputs(
        node: RuntimeNode,
        finalText: String,
        taskResults: Map<String, String>
    ): Map<String, String> = buildMap {
        put("nodeId", node.id)
        put("agentId", node.agentId ?: "auto")
        put("summary", finalText.take(1_200))
        taskResults.forEach { (k, v) -> put("dep.$k", v.take(1_200)) }
    }

    // ── Internal model ────────────────────────────────────────────────────────

    private data class RuntimePlan(
        val planId: String,
        val intent: String,
        val nodes: Map<String, RuntimeNode>
    )

    private data class RuntimeNode(
        val id: String,
        val agentId: String?,
        val dependencies: List<String>,
        val timeoutMs: Long,
        val input: String
    )

    private fun RuntimeNode.toExecutionNode() = ExecutionNode(
        nodeId = id,
        dependencies = dependencies,
        assignedAgent = agentId,
        timeoutMs = timeoutMs
    )
}
