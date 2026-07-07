package com.airi.assistant.agent.scheduler

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.airi.assistant.agent.subagent.SubAgentContext
import com.airi.assistant.agent.subagent.SubAgentRegistry
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.memory.AiriDatabase
import kotlinx.coroutines.flow.collect

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
 *   4. Always returns [Result.success] (failures are logged, not retried,
 *      to avoid WorkManager infinite loops from bad task definitions).
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
    }

    override suspend fun doWork(): Result {
        val jobId   = inputData.getString(KEY_JOB_ID)   ?: return Result.failure()
        val agentId = inputData.getString(KEY_AGENT_ID) ?: return Result.failure()
        val payload = inputData.getString(KEY_PAYLOAD)  ?: return Result.failure()
        val label   = inputData.getString(KEY_LABEL)    ?: agentId

        LoggingService.info(TAG, "AIRI_PROOF SCHEDULED_JOB_STARTED id=$jobId agent=$agentId label=$label")

        // AP-11: System maintenance payloads are handled directly — they don't route
        // through the agent/orchestrator stack because they are infrastructure tasks,
        // not user-facing agent actions.
        if (agentId == "system") {
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
                    else -> null  // unknown system payload — fall through to agent routing
                }
            }
            val handled = maintenanceResult.getOrNull()
            if (handled != null) {
                LoggingService.info(TAG, "AIRI_PROOF SCHEDULED_JOB_DONE id=$jobId output=$handled")
                return Result.success()
            }
            maintenanceResult.exceptionOrNull()?.let { err ->
                LoggingService.warn(TAG, "AIRI_PROOF SCHEDULED_MAINTENANCE_FAILED id=$jobId payload=$payload error=${err.message}")
            }
        }

        // Build a background SubAgentContext — no UI session, generous timeout
        val ctx = SubAgentContext(
            sessionId         = "scheduled_$jobId",
            userId            = "scheduled",
            recentTurns       = emptyList(),
            worldState        = mapOf("source" to "scheduled_task", "agent_id" to agentId),
            privacyLevel      = 1,
            allowedTools      = emptyList(),
            timeoutMs         = 120_000L   // 2-min budget for background tasks
        )

        val result = runCatching {
            val agent = SubAgentRegistry.route(payload, ctx)
            if (agent != null) {
                Log.i(TAG, "AIRI_PROOF SCHEDULED_JOB_ROUTED id=$jobId agent=${agent.capability.agentId}")
                // Collect the Flow to drive execution to completion
                var lastOutput = ""
                agent.execute(payload, ctx).collect { event ->
                    lastOutput = event.toString().take(200)
                }
                lastOutput
            } else {
                Log.i(TAG, "AIRI_PROOF SCHEDULED_JOB_ORCHESTRATOR id=$jobId (no agent matched)")
                val orch = runCatching { ServiceLocator.productionOrchestrator }.getOrNull()
                val execResult = orch?.executeSingle(payload, ctx)
                execResult?.toString() ?: "Scheduled task dispatched (no agent match)"
            }
        }

        result.onSuccess { output ->
            LoggingService.info(TAG, "AIRI_PROOF SCHEDULED_JOB_DONE id=$jobId output=${output?.toString()?.take(120)}")
            EventBus.emitSync(
                AppEvent.GenericInfo("✓ Scheduled task complete: $label")
            )
        }.onFailure { err ->
            LoggingService.warn(TAG, "AIRI_PROOF SCHEDULED_JOB_FAILED id=$jobId error=${err.message}")
            EventBus.emitSync(
                AppEvent.GenericInfo("⚠ Scheduled task failed: $label — ${err.message?.take(80)}")
            )
        }

        // Always succeed from WorkManager's perspective — task failures are
        // domain errors, not infrastructure failures. Returning Result.retry()
        // here would cause infinite loops for malformed task payloads.
        return Result.success()
    }
}
