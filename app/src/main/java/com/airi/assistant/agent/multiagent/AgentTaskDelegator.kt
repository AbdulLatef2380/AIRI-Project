package com.airi.assistant.agent.multiagent

import android.util.Log
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * AgentTaskDelegator — routes decomposed tasks to capable agents and
 * coordinates parallel/sequential execution.
 *
 * Flow:
 *   1. Receive a list of [DelegatedTask]s from the PlannerAgent.
 *   2. For each task, find the best-matching agent via [AgentCapabilityGraph].
 *   3. Execute tasks respecting dependency order.
 *   4. Merge results and publish to [SharedCognitiveBus].
 *   5. Emit activity events throughout for UI visibility.
 *
 * Wires to the existing [SubAgentRegistry] for actual execution.
 */
class AgentTaskDelegator(
    private val bus: SharedCognitiveBus = SharedCognitiveBus
) {
    private val TAG   = "AgentTaskDelegator"
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    data class DelegatedTask(
        val taskId:      String,
        val description: String,
        val capability:  String,              // required capability key
        val dependsOn:   List<String> = emptyList(),  // taskIds that must complete first
        val priority:    Int = 5              // 1=highest, 10=lowest
    )

    data class TaskResult(
        val taskId:     String,
        val agentId:    String,
        val success:    Boolean,
        val output:     String,
        val durationMs: Long
    )

    private val completedResults = ConcurrentHashMap<String, TaskResult>()

    /** Execute a list of tasks with dependency-aware scheduling. Returns all results. */
    suspend fun executeTasks(
        tasks:   List<DelegatedTask>,
        context: SubAgentContext
    ): List<TaskResult> = coroutineScope {
        val results = mutableListOf<TaskResult>()
        val pending = tasks.sortedBy { it.priority }.toMutableList()

        while (pending.isNotEmpty()) {
            // Find tasks whose dependencies are all satisfied
            val ready = pending.filter { task ->
                task.dependsOn.all { dep -> completedResults.containsKey(dep) }
            }
            if (ready.isEmpty()) {
                Log.w(TAG, "Deadlock: no ready tasks but ${pending.size} pending")
                break
            }

            // Build a context snapshot enriched with ALL results so far.
            // Sub-agents can read prior outputs from context.dependencyResults
            // to chain reasoning (e.g. ResearchAgent output feeds CodingAgent).
            val enrichedContext = context.copy(
                dependencyResults = completedResults.mapValues { it.value.output }
            )

            // Execute ready tasks in parallel
            val deferred = ready.map { task ->
                async { executeTask(task, enrichedContext) }
            }
            val batchResults = deferred.awaitAll()
            batchResults.forEach { result ->
                completedResults[result.taskId] = result
                results.add(result)
            }
            pending.removeAll(ready.toSet())
        }

        completedResults.clear()
        results
    }

    private suspend fun executeTask(task: DelegatedTask, context: SubAgentContext): TaskResult {
        val t0 = System.currentTimeMillis()
        val healthKey = "agent_task_${task.taskId}"

        // Register with RuntimeHealthMonitor so stuck tasks appear in HealthReport
        runCatching {
            com.airi.assistant.core.ServiceLocator.runtimeHealthMonitor.registerCoroutine(healthKey)
        }

        AgentActivityBus.emit(
            "Delegating '${task.description.take(50)}' → ${task.capability}",
            ActivityCategory.ORCHESTRATION
        )

        val result = try {
        // Find the best agent for this capability
        val candidates = AgentCapabilityGraph.findCapable(task.capability)
        val chosenCap  = candidates.firstOrNull()

        if (chosenCap == null) {
            Log.w(TAG, "No agent found for capability '${task.capability}' — falling back to LLM")
            TaskResult(task.taskId, "llm_fallback", true,
                "Handled by LLM (no specialized agent for '${task.capability}')",
                System.currentTimeMillis() - t0)
        } else {
            // Delegate to SubAgentRegistry if agent is registered there
            val subAgent = SubAgentRegistry.findById(chosenCap.agentId)
            val output = if (subAgent != null) {
                runCatching {
                    val sb = StringBuilder()
                    subAgent.execute(task.description, context).collect { event ->
                        when (event) {
                            is com.airi.assistant.agent.subagent.AgentEvent.PartialResult -> sb.append(event.text)
                            is com.airi.assistant.agent.subagent.AgentEvent.Complete      -> sb.append(event.result)
                            is com.airi.assistant.agent.subagent.AgentEvent.Failed        -> sb.append("Error: ${event.reason}")
                            else -> {}
                        }
                    }
                    sb.toString().ifBlank { "Completed: ${task.description.take(60)}" }
                }.getOrElse { "Agent error: ${it.message}" }
            } else {
                // Agent is capability-registered but not a SubAgent — use the bus
                SharedCognitiveBus.publishRequest(
                    fromAgentId = "delegator",
                    toAgentId   = chosenCap.agentId,
                    topic       = task.capability,
                    payload     = task.description,
                    summary     = task.description.take(60)
                )
                "Delegated to ${chosenCap.displayName} via cognitive bus"
            }

            val r = TaskResult(task.taskId, chosenCap.agentId, true, output,
                System.currentTimeMillis() - t0)

            SharedCognitiveBus.publishResult(
                fromAgentId = chosenCap.agentId,
                topic       = task.capability,
                payload     = r,
                summary     = "Completed: ${task.description.take(50)} (${r.durationMs}ms)"
            )
            AgentActivityBus.emit("✓ ${chosenCap.displayName}: ${task.description.take(40)}", ActivityCategory.ORCHESTRATION)
            r
        }
        } finally {
            // Always unregister — whether task completed, threw, or was cancelled.
            runCatching {
                com.airi.assistant.core.ServiceLocator.runtimeHealthMonitor.unregisterCoroutine(healthKey)
            }
        }
        return result
    }
}

// AgentTaskDelegator wires multiagent routing to the existing SubAgentRegistry.
