package com.airi.assistant.agent.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.airi.assistant.agent.orchestrator.ProductionAgentOrchestrator
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.memory.AiriDatabase
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * ScheduledAgentWorker — WorkManager [CoroutineWorker] that executes a
 * single scheduled job created by [ScheduledJobOrchestrator].
 *
 * ## Input Data (from [ScheduledJobOrchestrator.scheduleOnce])
 *
 *   - `job_id`   — UUID of the persisted [ScheduledJob]
 *   - `agent_id` — target sub-agent (e.g. "productivity", "research")
 *   - `payload`  — the task description / instruction text
 *   - `label`    — human-readable label for logging
 *
 * ## Execution
 *
 *   1. Resolves the correct sub-agent via [SubAgentRegistry.route].
 *   2. Executes the payload as a [SubAgentContext] with BACKGROUND priority.
 *   3. Emits [AppEvent.GenericInfo] on completion so the UI and
 *      [GlobalAgentEventDispatcher] can surface the result.
 *   4. Returns success for completed work, retries bounded transient failures,
 *      and records terminal failures for the task-management UI.
 *
 * ## Missing dispatch gap (previous state)
 *
 *   This class was referenced by [ScheduledJobOrchestrator] but never
 *   existed, causing every enqueued OneTimeWorkRequest to fail with a
 *   "Worker class not found" fatal at WorkManager runtime. This file
 *   closes that gap.
 */
class ScheduledAgentWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "ScheduledAgentWorker"

        const val KEY_JOB_ID   = "job_id"
        const val KEY_AGENT_ID = "agent_id"
        const val KEY_PAYLOAD  = "payload"
        const val KEY_LABEL    = "label"
        const val KEY_PROJECT_ID = "project_id"
        const val KEY_OWNER_ID = "owner_id"
        const val KEY_PRIVACY_LEVEL = "privacy_level"
        const val KEY_MANUAL_RUN = "manual_run"
        private const val MAX_RETRY_ATTEMPTS = 3

        private fun Throwable.isTransientFailure(): Boolean =
            this is IOException || this is UnknownHostException || this is SocketTimeoutException
    }

    override suspend fun doWork(): Result {
        val jobId   = inputData.getString(KEY_JOB_ID)   ?: return Result.failure()
        val agentId = inputData.getString(KEY_AGENT_ID) ?: return Result.failure()
        val payload = inputData.getString(KEY_PAYLOAD)  ?: return Result.failure()
        val label   = inputData.getString(KEY_LABEL)    ?: agentId
        val projectId = inputData.getString(KEY_PROJECT_ID)?.takeIf { it.isNotBlank() }
        val ownerId = inputData.getString(KEY_OWNER_ID)?.takeIf { it.isNotBlank() } ?: "scheduled"
        val privacyLevel = inputData.getInt(KEY_PRIVACY_LEVEL, SubAgentContext.PRIVACY_BALANCED)
            .coerceIn(SubAgentContext.PRIVACY_MAXIMUM, SubAgentContext.PRIVACY_STANDARD)
        val manualRunRequestId = id.toString().takeIf { inputData.getBoolean(KEY_MANUAL_RUN, false) }

        LoggingService.info(TAG, "AIRI SCHEDULED_JOB_STARTED id=$jobId agent=$agentId label=$label")

        // : System maintenance payloads are handled directly — they don't route
        // through the agent/orchestrator stack because they are infrastructure tasks,
        // not user-facing agent actions.
        if (agentId == ScheduledJobInputPolicy.SYSTEM_AGENT_ID) {
            if (!ScheduledJobInputPolicy.isAllowedSystemMaintenancePayload(payload)) {
                ScheduledJobOrchestrator(applicationContext).recordRunResult(
                    jobId = jobId,
                    outcome = ScheduledJobOutcome.FAILED,
                    completedManualRunRequestId = manualRunRequestId
                )
                LoggingService.warn(
                    TAG,
                    "AIRI SCHEDULED_MAINTENANCE_REJECTED id=$jobId payload=$payload"
                )
                return Result.failure()
            }
            val maintenanceResult = runCatching {
                when (payload) {
                    "sandbox_reaper" -> {
                        ServiceLocator.workspaceRegistry.pruneStale()
                        "Sandbox reaper: pruned stale workspaces"
                    }
                    "audit_log_pruner" -> {
                        val cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000
                        ServiceLocator.auditRepository.pruneOlderThan(cutoff)
                        "Audit log pruner: removed entries older than 30 days"
                    }
                    "context_cache_pruner" -> {
                        val now = System.currentTimeMillis()
                        AiriDatabase.getDatabase(applicationContext).contextCacheDao().cleanupOld(now)
                        "Context cache pruner: removed expired entries"
                    }
                    else -> error("Validated system maintenance payload was not handled")
                }
            }
            val handled = maintenanceResult.getOrNull()
            if (handled != null) {
                ScheduledJobOrchestrator(applicationContext)
                    .recordRunResult(
                        jobId = jobId,
                        outcome = ScheduledJobOutcome.COMPLETED,
                        completedManualRunRequestId = manualRunRequestId
                    )
                LoggingService.info(TAG, "Scheduled job completed id=$jobId")
                return Result.success()
            }
            maintenanceResult.exceptionOrNull()?.let { err ->
                LoggingService.warn(
                    TAG,
                    "AIRI SCHEDULED_MAINTENANCE_FAILED id=$jobId error=${err.javaClass.simpleName}"
                )
            }
        }

        // Agent jobs always use the production orchestrator so every background
        // execution has a DurableTask → Run → Step timeline. A scheduled job is
        // not allowed to invoke a sub-agent directly because that bypasses replay,
        // approvals, and failure diagnostics.
        val ctx = SubAgentContext(
            sessionId = "scheduled_$jobId",
            userId = ownerId,
            projectId = projectId,
            recentTurns = emptyList(),
            worldState = mapOf("source" to "scheduled_task", "agent_id" to agentId),
            privacyLevel = privacyLevel,
            allowedTools = emptyList(),
            timeoutMs = 120_000L
        )

        val result = runCatching {
            val orchestrator = ServiceLocator.productionOrchestrator
            val execution = orchestrator.executePlan(
                ProductionAgentOrchestrator.OrchestratorPlan(
                    tasks = listOf(
                        ProductionAgentOrchestrator.OrchestratorTask(
                            description = label.take(120),
                            agentId = agentId.takeUnless { it == "system" },
                            dependencies = emptyList(),
                            input = payload,
                            context = ctx
                        )
                    ),
                    projectId = ctx.projectId,
                    ownerId = ctx.userId
                )
            )
            when (execution) {
                is ProductionAgentOrchestrator.ExecutionResult.Success -> {
                    ScheduledExecution(taskId = execution.planId)
                }
                is ProductionAgentOrchestrator.ExecutionResult.PartialFailure -> {
                    throw ScheduledExecutionFailure(
                        taskId = execution.planId,
                        reason = execution.taskErrors.values.joinToString().ifBlank { "Scheduled execution failed" }
                    )
                }
            }
        }

        return result.fold(
            onSuccess = { execution ->
                ScheduledJobOrchestrator(applicationContext).recordRunResult(
                    jobId = jobId,
                    outcome = ScheduledJobOutcome.COMPLETED,
                    durableTaskId = execution.taskId,
                    completedManualRunRequestId = manualRunRequestId
                )
                LoggingService.info(TAG, "Scheduled job completed id=$jobId task=${execution.taskId}")
                EventBus.emitSync(AppEvent.GenericInfo("Scheduled task complete: $label"))
                Result.success()
            },
            onFailure = { error ->
                val transient = error.isTransientFailure()
                val canRetry = transient && runAttemptCount < MAX_RETRY_ATTEMPTS
                ScheduledJobOrchestrator(applicationContext).recordRunResult(
                    jobId = jobId,
                    outcome = if (canRetry) ScheduledJobOutcome.RETRYING else ScheduledJobOutcome.FAILED,
                    durableTaskId = (error as? ScheduledExecutionFailure)?.taskId,
                    completedManualRunRequestId = manualRunRequestId
                )
                LoggingService.warn(
                    TAG,
                    "Scheduled job failed id=$jobId transient=$transient attempt=$runAttemptCount error=${error.javaClass.simpleName}"
                )
                EventBus.emitSync(
                    AppEvent.GenericInfo(
                        if (canRetry) "Scheduled task will retry: $label"
                        else "Scheduled task failed: $label"
                    )
                )
                if (canRetry) Result.retry() else Result.failure()
            }
        )
    }

    private data class ScheduledExecution(val taskId: String)

    private class ScheduledExecutionFailure(
        val taskId: String,
        reason: String
    ) : IllegalStateException(reason)
}
