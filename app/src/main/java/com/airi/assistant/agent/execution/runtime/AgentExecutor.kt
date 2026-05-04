package com.airi.assistant.agent.execution.runtime

import android.util.Log
import com.airi.assistant.agent.execution.command.CommandRouter
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.PlanStep
import com.airi.assistant.agent.workspace.WorkspaceRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Legacy flat-plan executor.
 *
 * This is the original single-job executor wired to [CommandRouter].
 * The modern path is [com.airi.assistant.core.UnifiedCognitiveLoop.executeGraph]
 * + [com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator], which
 * use [com.airi.assistant.agent.planning.TypedPlanGraph] DAG execution.
 *
 * This class is kept for backward compatibility with [ActionPlan]-producing
 * callers (e.g. [com.airi.assistant.agent.planning.GoalExecutor]).
 *
 * Thread-safety:
 *   - [currentContext] and [activeJob] are @Volatile; all writes are paired
 *     so a concurrent [getCurrentStatus] always sees a consistent state.
 *   - Calling [execute] while a job is in-flight cancels the previous job
 *     before starting the new one — no orphaned coroutines.
 *   - Rollback on failure is performed via [WorkspaceRegistry] instead of
 *     the previous no-op println stub.
 */
object AgentExecutor {

    private const val TAG = "AgentExecutor"

    @Volatile private var currentContext: ExecutionContext? = null
    @Volatile private var activeJob: Job? = null

    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Execute [plan] asynchronously.
     *
     * Any previously running plan is cancelled first to prevent concurrent
     * execution with stale [currentContext] overwrite.
     */
    fun execute(plan: ActionPlan) {
        // Cancel any in-flight execution before replacing currentContext.
        activeJob?.cancel()

        val context = ExecutionContext(plan = plan)
        currentContext = context
        context.state  = ExecutionState.PLANNING

        if (plan.requiresConfirmation) {
            context.state = ExecutionState.WAITING_CONFIRMATION
            Log.d(TAG, "Plan requires confirmation — halted at gate")
            return
        }

        activeJob = executorScope.launch {
            runExecution(context)
        }
    }

    /**
     * Cancel the active execution and roll back any workspace changes.
     */
    fun cancel() {
        activeJob?.cancel()
        activeJob = null
        currentContext?.let { ctx ->
            ctx.state = ExecutionState.FAILED
            runCatching { WorkspaceRegistry.get("agent_executor").rollbackLatest() }
        }
        Log.w(TAG, "Execution cancelled by caller")
    }

    /** Current execution state — null if no plan has been submitted. */
    fun getCurrentStatus(): ExecutionState? = currentContext?.state

    // ── Internal ─────────────────────────────────────────────────────────────

    private suspend fun runExecution(context: ExecutionContext) {
        context.state = ExecutionState.EXECUTING
        val workspace = WorkspaceRegistry.get("agent_executor")
        workspace.snapshot()

        try {
            for ((index, step) in context.plan.steps.withIndex()) {
                if (!currentCoroutineContext().isActive) {
                    Log.d(TAG, "Execution cancelled at step $index")
                    break
                }
                context.currentStepIndex = index

                val result = executeStep(step)
                context.stepHistory.add(result)

                if (!result.success) {
                    handleFailure(context, workspace)
                    return
                }
            }

            context.state = ExecutionState.COMPLETED
            Log.i(TAG, "AIRI_PROOF AGENT_EXECUTOR_DONE steps=${context.plan.steps.size}")
        } finally {
            // Release the workspace whether execution completed, failed, or was
            // cancelled. Without this, every execute() call accumulates an entry
            // in WorkspaceRegistry for the lifetime of the process.
            WorkspaceRegistry.release("agent_executor")
        }
    }

    private suspend fun executeStep(step: PlanStep): StepResult {
        // No artificial delay — CommandRouter is synchronous/fast.
        val result = CommandRouter.execute(step)
        Log.d(TAG, "step=${step.id} success=${result.success} msg=${result.message?.take(60)}")
        return StepResult(
            stepName = step.id,
            success  = result.success,
            message  = result.message
        )
    }

    /**
     * Trigger workspace rollback and update state.
     * The previous implementation used println — replaced with real rollback.
     */
    private fun handleFailure(context: ExecutionContext, workspace: com.airi.assistant.agent.workspace.SandboxWorkspace) {
        context.state = ExecutionState.ROLLING_BACK
        val rolled = runCatching { workspace.rollbackLatest() }.getOrDefault(false)
        Log.w(TAG, "AIRI_PROOF AGENT_EXECUTOR_ROLLBACK rolled=$rolled at step=${context.currentStepIndex}")
        context.state = ExecutionState.FAILED
    }
}
