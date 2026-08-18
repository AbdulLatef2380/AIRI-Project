package com.airi.assistant.agent.orchestrator

import android.util.Log
import com.airi.assistant.agent.learning.reinforcement.ReinforcementMemory
import com.airi.assistant.agent.planning.GoalNode
import com.airi.assistant.agent.planning.GraphSnapshot
import com.airi.assistant.agent.planning.NodeStatus
import com.airi.assistant.agent.planning.RecoveryBranch
import com.airi.assistant.agent.reflection.AdaptiveRetryPolicy
import com.airi.assistant.agent.reflection.RecoveryStrategy
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.agent.workspace.AgentWorkspace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * ProductionAgentOrchestrator — parallel sub-agent execution engine.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CAPABILITIES
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   - Parallel branches: tasks with no unresolved dependencies run simultaneously
 *   - Dependency graphs: step A must complete before step B starts (DAG)
 *   - Result propagation: completed task results flow to dependent tasks via
 *     SubAgentContext.dependencyResults
 *   - Cancellation: structured — cancelling the orchestration scope cancels
 *     ALL running agents cleanly
 *   - Recovery: per-task retry with backoff, independent of sibling tasks
 *   - Observability: all events emitted to the [executionState] StateFlow
 *
 * ─────────────────────────────────────────────────────────────────────────
 * TASK GRAPH EXAMPLE
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Plan:
 *     Task A: "Search for X"            (dependencies: [])
 *     Task B: "Search for Y"            (dependencies: [])
 *     Task C: "Synthesize A and B"      (dependencies: [A.id, B.id])
 *
 *   Execution:
 *     t=0   → A and B start in parallel
 *     t=2s  → A completes, B completes
 *     t=2s  → C starts with dependencyResults = {A.id: resultA, B.id: resultB}
 *     t=5s  → C completes → plan result
 *
 * ─────────────────────────────────────────────────────────────────────────
 * INTEGRATION WITH EXISTING ARCHITECTURE
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   The orchestrator sits ABOVE [AgentController] (the existing skill→plan→LLM
 *   pipeline) and ABOVE [AgentService]. For simple single-agent tasks, use
 *   [AgentService] directly. For multi-step or parallel tasks, use this class.
 *
 *   AgentEvent.Delegate is resolved here via SubAgentRegistry.findById.
 *   AgentEvent.Delegate with targetAgentId="llm_backend" is surfaced to the
 *   caller for routing to HybridOrchestrator.
 */
class ProductionAgentOrchestrator {

    private val TAG = "ProductionOrchestrator"

    // ── Observability hook — set by ServiceLocator after construction ─────────
    //
    // Nullable so the orchestrator compiles cleanly without a circular init
    // dependency. ServiceLocator sets this immediately after `also { orch -> }`.

    @Volatile
    var observabilityHub: com.airi.assistant.agent.observability.AgentObservabilityHub? = null

    /** DurableTaskManager hook — set by ServiceLocator after construction. */
    @Volatile
    var durableTaskManager: com.airi.assistant.agent.durable.DurableTaskManager? = null

    /**
     * Adaptive retry policy tracks per-agent-type failure rates across tasks.
     * When an agent type fails ≥65% of the time over ≥3 samples,
     * selectStrategy() escalates to ABORT instead of wasting retry budget.
     */
    private val adaptiveRetryPolicy = AdaptiveRetryPolicy()

    // ── Orchestration scope — SupervisorJob so task failures don't kill siblings ──

    private val orchestrationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // ── Active execution tracking ─────────────────────────────────────────────

    private val activeExecutions = ConcurrentHashMap<String, OrchestratorExecution>()

    private val _state = MutableStateFlow<OrchestratorState>(OrchestratorState.Idle)
    val state: StateFlow<OrchestratorState> = _state.asStateFlow()

    // ─────────────────────────────────────────────────────────────────────────
    // Single-task convenience API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Execute a single task with automatic sub-agent routing.
     *
     * Returns [ExecutionResult] when complete. Respects [context.timeoutMs].
     * Emits events via [onEvent] for streaming UI updates.
     */
    suspend fun executeSingle(
        input:   String,
        context: SubAgentContext,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): ExecutionResult {
        val task = OrchestratorTask(
            id          = UUID.randomUUID().toString(),
            description = input.take(120),
            agentId     = null,          // auto-route
            dependencies = emptyList(),
            input       = input,
            context     = context
        )
        return executePlan(OrchestratorPlan(tasks = listOf(task)), onEvent)
    }

    /**
     * Execute a multi-step plan with dependency graph.
     *
     * Tasks with empty [OrchestratorTask.dependencies] run in parallel immediately.
     * Subsequent tasks run when all their declared dependencies complete.
     */
    suspend fun executePlan(
        plan:    OrchestratorPlan,
        onEvent: suspend (AgentEvent) -> Unit = {}
    ): ExecutionResult {
        val executionId = plan.id
        val startMs     = System.currentTimeMillis()

        Log.i(TAG, "AIRI PLAN_START id=$executionId tasks=${plan.tasks.size}")
        _state.value = OrchestratorState.Running(executionId, plan.tasks.size, 0)

        // Per-plan shared tool workspace — agents publish/consume typed artifacts here
        val workspace = AgentWorkspace(workspaceId = executionId)

        // Span: root plan trace
        val planSpanId = observabilityHub?.startSpan(
            name       = "plan:${plan.tasks.firstOrNull()?.description?.take(40) ?: executionId}",
            attributes = mapOf("task_count" to plan.tasks.size.toString())
        )

        // Results accumulated per task: taskId → result text
        val taskResults   = ConcurrentHashMap<String, String>()
        val taskErrors    = ConcurrentHashMap<String, String>()
        val completedIds  = ConcurrentHashMap.newKeySet<String>()
        val allEvents     = mutableListOf<AgentEvent>()

        // Topological execution: repeatedly find tasks whose dependencies are met
        var remaining = plan.tasks.toMutableList()
        var iterationGuard = 0

        while (remaining.isNotEmpty() && orchestrationScope.isActive) {
            iterationGuard++
            if (iterationGuard > plan.tasks.size * 2) {
                Log.e(TAG, "Cycle detected in task dependency graph — aborting")
                break
            }

            // Find all tasks whose dependencies are fully resolved
            val ready = remaining.filter { task ->
                task.dependencies.all { depId -> completedIds.contains(depId) }
            }

            if (ready.isEmpty()) {
                // No task is ready — dependency cycle or all blocked by errors
                Log.w(TAG, "No ready tasks — possible dependency cycle or prior failures")
                break
            }

            remaining.removeAll(ready)

            // Execute all ready tasks in parallel
            val deferred = ready.map { task ->
                orchestrationScope.async {
                    // Inject workspace-resolved artifacts alongside dependency results
                    val workspaceInjection = workspace.resolveDependency(task.id)
                    val enrichedContext = task.context.copy(
                        dependencyResults = taskResults.toMap() + workspaceInjection,
                        parentTaskId      = executionId
                    )
                    val result = executeTask(task, enrichedContext, onEvent, allEvents, workspace)
                    when (result) {
                        is TaskResult.Success -> {
                            taskResults[task.id] = result.text
                            completedIds.add(task.id)
                            // Publish result to workspace for downstream agents
                            if (result.text.isNotBlank()) {
                                workspace.putText(task.id, result.text, task.id)
                            }
                            // Reinforce success signal
                            task.agentId?.let { ReinforcementMemory.recordSuccess("routing", it) }
                        }
                        is TaskResult.Failure -> {
                            taskErrors[task.id] = result.reason
                            // Reinforce failure signal
                            task.agentId?.let { ReinforcementMemory.recordFailure("routing", it) }
                            Log.w(TAG, "Task ${task.id} failed: ${result.reason}")
                        }
                    }
                    result
                }
            }

            // Wait for all parallel tasks to complete before advancing the wave
            deferred.awaitAll()

            val completed = completedIds.size
            val total     = plan.tasks.size
            _state.value  = OrchestratorState.Running(executionId, total, completed)
            Log.d(TAG, "Wave complete: $completed/$total tasks done")

            // ── Push live graph snapshot to observability hub ──────────────────
            // Convert OrchestratorTask states to GoalNodes so the Graph tab in
            // ObservabilityScreen can show live plan progress in real time.
            val waveGraphNodes = plan.tasks.map { t ->
                val nodeStatus = when {
                    completedIds.contains(t.id)    -> NodeStatus.DONE
                    taskErrors.containsKey(t.id)   -> NodeStatus.FAILED
                    ready.any { r -> r.id == t.id } -> NodeStatus.RUNNING
                    else                            -> NodeStatus.PENDING
                }
                GoalNode(
                    id             = t.id,
                    description    = t.description.take(80),
                    action         = t.agentId ?: "auto",
                    params         = emptyMap(),
                    dependsOn      = t.dependencies,
                    recoveryBranch = RecoveryBranch.Retry(2),
                    isCritical     = true
                ).also { it.status = nodeStatus }
            }
            observabilityHub?.updateGraphSnapshot(
                GraphSnapshot(
                    goalId       = executionId,
                    description  = plan.tasks.firstOrNull()?.description?.take(60) ?: executionId,
                    totalNodes   = total,
                    doneNodes    = completedIds.size,
                    failedNodes  = taskErrors.size,
                    skippedNodes = 0,
                    nodes        = waveGraphNodes
                )
            )
        }

        val durationMs = System.currentTimeMillis() - startMs

        val succeeded = taskErrors.isEmpty()
        planSpanId?.let {
            observabilityHub?.endSpan(
                spanId     = it,
                success    = succeeded,
                attributes = mapOf(
                    "duration_ms"   to durationMs.toString(),
                    "tasks_ok"      to completedIds.size.toString(),
                    "tasks_failed"  to taskErrors.size.toString(),
                    "workspace_keys" to workspace.keys().size.toString()
                )
            )
        }
        workspace.clear()

        return if (succeeded) {
            val finalResult = taskResults.values.lastOrNull() ?: ""
            Log.i(TAG, "AIRI PLAN_SUCCESS id=$executionId duration=${durationMs}ms")
            _state.value = OrchestratorState.Idle
            ExecutionResult.Success(
                planId        = executionId,
                taskResults   = taskResults.toMap(),
                finalResult   = finalResult,
                durationMs    = durationMs,
                eventsEmitted = allEvents.toList()
            )
        } else {
            Log.w(TAG, "AIRI PLAN_PARTIAL id=$executionId errors=${taskErrors.size}")
            _state.value = OrchestratorState.Idle
            ExecutionResult.PartialFailure(
                planId      = executionId,
                taskResults = taskResults.toMap(),
                taskErrors  = taskErrors.toMap(),
                durationMs  = durationMs
            )
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Task execution engine
    // ─────────────────────────────────────────────────────────────────────────

    private suspend fun executeTask(
        task:         OrchestratorTask,
        context:      SubAgentContext,
        onEvent:      suspend (AgentEvent) -> Unit,
        allEvents:    MutableList<AgentEvent>,
        workspace:    AgentWorkspace = AgentWorkspace(),
        retryAttempt: Int = 0
    ): TaskResult {
        Log.d(TAG, "Executing task=${task.id} agentId=${task.agentId ?: "auto"} attempt=$retryAttempt")

        // ── Span: per-task trace ────────────────────────────────────────────────
        val taskSpanId = observabilityHub?.startSpan(
            name       = "task:${task.description.take(50)}",
            attributes = mapOf("agent_id" to (task.agentId ?: "auto"), "task_id" to task.id)
        )

        // Resolve agent
        val agent = if (task.agentId != null) {
            SubAgentRegistry.findById(task.agentId)
                ?: return TaskResult.Failure("Agent '${task.agentId}' not found in registry")
        } else {
            SubAgentRegistry.route(task.input, context)
                ?: return TaskResult.Failure("No agent matched for: '${task.input.take(60)}'")
        }

        Log.i(TAG, "AIRI TASK_DISPATCH agent=${agent.capability.agentId} task=${task.id}")

        var resultText = ""
        var taskError: String? = null
        val toolsUsed = mutableListOf<String>()

        val timeoutMs = if (context.timeoutMs > 0) context.timeoutMs else 30_000L

        val timedOut = withTimeoutOrNull(timeoutMs) {
            runCatching {
                agent.execute(task.input, context)
                    .onCompletion { throwable ->
                        if (throwable != null && throwable !is CancellationException) {
                            Log.e(TAG, "Agent flow error: ${throwable.message}")
                        }
                    }
                    .catch { throwable ->
                        if (throwable !is CancellationException) {
                            emit(AgentEvent.Failed("Agent threw exception: ${throwable.message}", false))
                        }
                    }
                    .collect { event ->
                        allEvents.add(event)
                        onEvent(event)

                        when (event) {
                            is AgentEvent.PartialResult -> {
                                resultText += event.text
                            }
                            is AgentEvent.Complete -> {
                                resultText   = event.result
                                toolsUsed.addAll(event.toolsUsed)
                                Log.i(TAG, "AIRI TASK_COMPLETE task=${task.id} " +
                                        "agent=${agent.capability.agentId} " +
                                        "duration=${event.durationMs}ms")
                                // ── Observability: record success ─────────────
                                observabilityHub?.recordAgentSuccess(
                                    agentId    = agent.capability.agentId,
                                    durationMs = event.durationMs
                                )
                                // ── Record success in StrategyEvolutionEngine ──
                                com.airi.assistant.core.ServiceLocator.strategyEvolutionEngine
                                    .recordNodeOutcome(agent.capability.agentId, "direct", 1, true)
                            }
                            is AgentEvent.Failed -> {
                                taskError = event.reason
                                Log.w(TAG, "AIRI TASK_FAILED task=${task.id} reason=${event.reason}")
                                // ── Observability: record error ───────────────
                                observabilityHub?.recordAgentError(
                                    agentId = agent.capability.agentId,
                                    reason  = event.reason
                                )
                                // ── Record failure in StrategyEvolutionEngine ──
                                com.airi.assistant.core.ServiceLocator.strategyEvolutionEngine
                                    .recordNodeOutcome(agent.capability.agentId, "direct", 1, false)
                            }
                            is AgentEvent.Delegate -> {
                                // Delegation to another sub-agent — resolve recursively
                                if (event.targetAgentId != "llm_backend") {
                                    val delegateResult = resolveDelegation(event, context, onEvent, allEvents)
                                    if (delegateResult != null) resultText += delegateResult
                                }
                                // "llm_backend" delegation is surfaced to caller via onEvent
                            }
                            is AgentEvent.ToolCall -> {
                                toolsUsed.add(event.toolName)
                                Log.d(TAG, "AIRI TOOL_CALL tool=${event.toolName} task=${task.id}")
                                // ── Observability: record every real tool call ─
                                observabilityHub?.recordToolCall(event.toolName)
                                // ── Checkpoint after each tool call ──
                                durableTaskManager?.updateCheckpoint(
                                    taskId          = task.id,
                                    checkpointData  = "tool:${event.toolName}",
                                    progressMessage = "Used tool: ${event.toolName}"
                                )
                            }
                            is AgentEvent.Progress -> {
                                Log.d(TAG, "Progress [${event.percentComplete}%] ${event.message}")
                            }
                        }
                    }
            }.onFailure { e ->
                if (e !is CancellationException) {
                    taskError = "Agent execution failed: ${e.message}"
                    Log.e(TAG, "Task ${task.id} exception: ${e.message}")
                }
            }
        }

        if (timedOut == null) {
            val reason = "Task timed out after ${timeoutMs}ms"
            Log.w(TAG, "Task ${task.id} timed out after ${timeoutMs}ms")
            // On timeout, allow one retry with half the time budget
            if (retryAttempt == 0) {
                Log.i(TAG, "Recovery: timeout retry attempt=1 task=${task.id}")
                val retryContext = context.copy(timeoutMs = timeoutMs / 2)
                val retryResult  = executeTask(
                    task.copy(context = retryContext), retryContext,
                    onEvent, allEvents, workspace, retryAttempt + 1
                )
                taskSpanId?.let {
                    observabilityHub?.endSpan(it, success = retryResult is TaskResult.Success,
                        attributes = mapOf("recovery" to "timeout_retry",
                                           "attempt"  to "1"))
                }
                return retryResult
            }
            taskSpanId?.let { observabilityHub?.endSpan(it, success = false,
                attributes = mapOf("failure" to "timeout", "attempt" to retryAttempt.toString())) }
            return TaskResult.Failure(reason)
        }

        return if (taskError == null) {
            adaptiveRetryPolicy.recordSuccess(agent.capability.agentId)
            taskSpanId?.let { observabilityHub?.endSpan(it, success = true,
                attributes = mapOf("result_len" to resultText.length.toString())) }
            TaskResult.Success(text = resultText, toolsUsed = toolsUsed)
        } else {
            val error = taskError!!
            // Classify error: network/timeout errors get one retry; permission/logic errors abort.
            val shouldAbort = error.contains("permission", ignoreCase = true) ||
                              error.contains("denied", ignoreCase = true) ||
                              error.contains("not found", ignoreCase = true) ||
                              error.contains("invalid", ignoreCase = true)
            val adaptiveDecision = adaptiveRetryPolicy.selectStrategy(
                actionType     = agent.capability.agentId,
                failureMessage = error,
                attemptNumber  = retryAttempt + 1
            )
            val abort = shouldAbort || adaptiveDecision.strategy.name == "ABORT" || retryAttempt >= 2
            adaptiveRetryPolicy.recordFailure(agent.capability.agentId)

            Log.i(TAG, "Recovery: abort=$abort adaptive=${adaptiveDecision.strategy} " +
                "attempt=$retryAttempt task=${task.id} error=${error.take(80)}")

            val finalResult: TaskResult = if (abort) {
                TaskResult.Failure(error)
            } else {
                Log.i(TAG, "Recovery: retrying task=${task.id} attempt=${retryAttempt + 1}")
                executeTask(task, context, onEvent, allEvents, workspace, retryAttempt + 1)
            }
            taskSpanId?.let {
                observabilityHub?.endSpan(it, success = finalResult is TaskResult.Success,
                    attributes = mapOf(
                        "abort"    to abort.toString(),
                        "attempt"  to retryAttempt.toString(),
                        "error"    to error.take(120)
                    ))
            }
            finalResult
        }
    }

    /**
     * Resolve an [AgentEvent.Delegate] to a sub-agent recursively.
     * Guards against infinite delegation via [SubAgentContext.nestingDepth].
     */
    private suspend fun resolveDelegation(
        delegation: AgentEvent.Delegate,
        context:    SubAgentContext,
        onEvent:    suspend (AgentEvent) -> Unit,
        allEvents:  MutableList<AgentEvent>
    ): String? {
        if (!context.canDelegate) {
            Log.w(TAG, "Max nesting depth reached — dropping delegation to ${delegation.targetAgentId}")
            return null
        }
        val subTask = OrchestratorTask(
            id           = UUID.randomUUID().toString(),
            description  = delegation.subInput.take(80),
            agentId      = delegation.targetAgentId,
            dependencies = emptyList(),
            input        = delegation.subInput,
            context      = context.copy(nestingDepth = context.nestingDepth + 1)
        )
        val result = executeTask(subTask, subTask.context, onEvent, allEvents)
        return (result as? TaskResult.Success)?.text
    }

    /**
     * Cancel all running executions. Safe to call at any time.
     */
    fun cancelAll() {
        orchestrationScope.cancel()
        _state.value = OrchestratorState.Idle
        Log.i(TAG, "All orchestrations cancelled")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * A plan is an ordered list of tasks with dependency edges.
     * Tasks without dependencies can run in parallel.
     */
    data class OrchestratorPlan(
        val id:    String              = UUID.randomUUID().toString(),
        val tasks: List<OrchestratorTask>
    )

    /**
     * A single executable unit in a plan.
     */
    data class OrchestratorTask(
        /** Stable ID referenced by [dependencies] of downstream tasks. */
        val id: String = UUID.randomUUID().toString(),

        /** Human-readable label for observability. */
        val description: String,

        /**
         * Target agent ID. Null = auto-route via SubAgentRegistry.
         * Use [SubAgentCapability.agentId] values.
         */
        val agentId: String?,

        /**
         * IDs of tasks that must complete before this task starts.
         * Empty = this task is a root (can start immediately).
         */
        val dependencies: List<String>,

        /** Input text passed to the agent. */
        val input: String,

        /** Execution context for this task. */
        val context: SubAgentContext
    )

    sealed class TaskResult {
        data class Success(val text: String, val toolsUsed: List<String> = emptyList()) : TaskResult()
        data class Failure(val reason: String) : TaskResult()
    }

    sealed class ExecutionResult {
        data class Success(
            val planId:        String,
            val taskResults:   Map<String, String>,
            val finalResult:   String,
            val durationMs:    Long,
            val eventsEmitted: List<AgentEvent>
        ) : ExecutionResult()

        data class PartialFailure(
            val planId:      String,
            val taskResults: Map<String, String>,
            val taskErrors:  Map<String, String>,
            val durationMs:  Long
        ) : ExecutionResult()
    }

    sealed class OrchestratorState {
        object Idle : OrchestratorState()
        data class Running(
            val executionId:    String,
            val totalTasks:     Int,
            val completedTasks: Int
        ) : OrchestratorState() {
            val progressPercent: Int
                get() = if (totalTasks == 0) 0 else (completedTasks * 100) / totalTasks
        }
    }

    private data class OrchestratorExecution(
        val id:  String,
        val job: Job
    )
}
