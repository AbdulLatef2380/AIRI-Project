package com.airi.assistant.agent.scheduler

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * ScheduledJobOrchestrator — durable scheduled agent task management.
 *
 * REAL EXECUTION:
 *   - Persists scheduled jobs to SharedPreferences (JSON) so they survive
 *     app restarts and process death.
 *   - Uses WorkManager for reliable background scheduling:
 *       - ONE_TIME: fires once at [delayMs] from now.
 *       - PERIODIC: fires every [intervalMinutes] (min 15 min — WorkManager floor).
 *   - Each job carries a [ScheduledJob.agentId] and [ScheduledJob.payload]
 *     that the [ScheduledAgentWorker] dispatches to the sub-agent routing layer.
 *   - Jobs can be cancelled, listed, and queried by id or agentId.
 *
 * WIRING:
 *   - [ServiceLocator.scheduledJobOrchestrator] holds the singleton.
 *   - [ProductionAgentOrchestrator] calls [schedule] when a plan step has
 *     type "SCHEDULE" (e.g., "remind me in 30 minutes to call John").
 *   - The UI can expose [listJobs] for a scheduled-task management screen.
 */
class ScheduledJobOrchestrator(private val context: Context) {

    companion object {
        private const val TAG          = "ScheduledJobOrchestrator"
        private const val PREFS_NAME   = "airi_scheduled_jobs"
        private const val KEY_JOBS     = "jobs_v1"
        private const val MIN_PERIODIC_MINUTES = 15L
        private const val SYSTEM_AGENT_ID = "system"
    }

    private val prefs      = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val workManager = WorkManager.getInstance(context)

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Schedule a one-time agent job to fire after [delayMs] milliseconds.
     *
     * @return The [ScheduledJob] descriptor (persisted immediately).
     */
    fun scheduleOnce(
        agentId:     String,
        payload:     String,
        label:       String,
        delayMs:     Long,
        requiresNet: Boolean = false,
        projectId:   String? = null,
        ownerId:     String = "scheduled",
        privacyLevel: Int = 1
    ): ScheduledJob {
        ScheduledJobInputPolicy.requireValid(agentId, payload, label)
        val safeDelayMs = delayMs.coerceAtLeast(0L)
        val job = ScheduledJob(
            id          = UUID.randomUUID().toString(),
            agentId     = agentId,
            payload     = payload,
            label       = label,
            type        = ScheduleType.ONE_TIME,
            triggerAtMs = System.currentTimeMillis() + safeDelayMs,
            intervalMs  = null,
            requiresNetwork = requiresNet,
            projectId = projectId,
            ownerId = ownerId,
            privacyLevel = privacyLevel.coerceIn(0, 2)
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresNet) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val data = Data.Builder()
            .putString("job_id",   job.id)
            .putString("agent_id", agentId)
            .putString("payload",  payload)
            .putString("label",    label)
            .putString("project_id", projectId)
            .putString("owner_id", ownerId)
            .putInt("privacy_level", privacyLevel.coerceIn(0, 2))
            .build()
        val request = OneTimeWorkRequestBuilder<ScheduledAgentWorker>()
            .setInputData(data)
            .setConstraints(constraints)
            .setInitialDelay(safeDelayMs, TimeUnit.MILLISECONDS)
            .addTag("airi_job_${job.id}")
            .addTag("airi_agent_$agentId")
            .build()

        val persistedJob = job.copy(workRequestId = request.id.toString())
        persistJob(persistedJob)
        workManager.enqueueUniqueWork(uniqueWorkName(job.id), ExistingWorkPolicy.KEEP, request)
        Log.i(TAG, "Scheduled job queued id=${job.id} agent=$agentId delayMs=$safeDelayMs")
        EventBus.emitSync(AppEvent.GenericInfo("ScheduledJob queued: $label"))
        return persistedJob
    }

    /**
     * Schedule a repeating agent job every [intervalMinutes] minutes.
     * WorkManager enforces a 15-minute floor.
     */
    fun schedulePeriodic(
        agentId:         String,
        payload:         String,
        label:           String,
        intervalMinutes: Long,
        requiresNet:     Boolean = false,
        stableJobId:     String? = null,
        projectId:       String? = null,
        ownerId:         String = "scheduled",
        privacyLevel:    Int = 1
    ): ScheduledJob {
        ScheduledJobInputPolicy.requireValid(agentId, payload, label)
        stableJobId?.let { id ->
            listJobs().firstOrNull { it.id == id }?.let { existing ->
                Log.i(TAG, "AIRI PERIODIC_JOB_REUSED id=$id agent=${existing.agentId}")
                return existing
            }
        }

        val safeInterval = intervalMinutes.coerceAtLeast(MIN_PERIODIC_MINUTES)
        val job = ScheduledJob(
            id          = stableJobId ?: UUID.randomUUID().toString(),
            agentId     = agentId,
            payload     = payload,
            label       = label,
            type        = ScheduleType.PERIODIC,
            triggerAtMs = System.currentTimeMillis() + safeInterval * 60_000L,
            intervalMs  = safeInterval * 60_000L,
            requiresNetwork = requiresNet,
            projectId = projectId,
            ownerId = ownerId,
            privacyLevel = privacyLevel.coerceIn(0, 2)
        )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (requiresNet) NetworkType.CONNECTED else NetworkType.NOT_REQUIRED)
            .build()
        val data = Data.Builder()
            .putString("job_id",   job.id)
            .putString("agent_id", agentId)
            .putString("payload",  payload)
            .putString("label",    label)
            .putString("project_id", projectId)
            .putString("owner_id", ownerId)
            .putInt("privacy_level", privacyLevel.coerceIn(0, 2))
            .build()
        val request = PeriodicWorkRequestBuilder<ScheduledAgentWorker>(
            safeInterval, TimeUnit.MINUTES
        )
            .setInputData(data)
            .setConstraints(constraints)
            .addTag("airi_job_${job.id}")
            .addTag("airi_agent_$agentId")
            .build()

        val persistedJob = job.copy(workRequestId = request.id.toString())
        persistJob(persistedJob)
        workManager.enqueueUniquePeriodicWork(
            uniqueWorkName(job.id),
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
        Log.i(TAG, "AIRI PERIODIC_JOB_QUEUED id=${job.id} agent=$agentId intervalMin=$safeInterval")
        return persistedJob
    }

    /** Cancel a scheduled job by its [jobId]. */
    fun cancel(jobId: String): Boolean {
        workManager.cancelUniqueWork(uniqueWorkName(jobId))
        val removed = removePersistedJob(jobId)
        Log.i(TAG, "Job cancelled id=$jobId removed=$removed")
        return removed
    }

    /** Cancel all jobs for a specific [agentId]. */
    fun cancelByAgent(agentId: String) {
        workManager.cancelAllWorkByTag("airi_agent_$agentId")
        val all     = listJobs()
        val keep    = all.filter { it.agentId != agentId }
        persistAllJobs(keep)
        Log.i(TAG, "Cancelled all jobs for agent=$agentId count=${all.size - keep.size}")
    }

    /**
     * Cancel all user-created jobs while preserving AIRI maintenance jobs.
     * System jobs use the reserved `system` agent id and must remain available
     * for sandbox, audit-log, and context-cache maintenance.
     */
    fun cancelAllUserJobs(): Int {
        val userJobs = listJobs().filter { it.agentId != SYSTEM_AGENT_ID }
        userJobs.forEach { job ->
            workManager.cancelUniqueWork(uniqueWorkName(job.id))
        }
        if (userJobs.isNotEmpty()) {
            val cancelledIds = userJobs.mapTo(mutableSetOf()) { it.id }
            persistAllJobs(listJobs().filterNot { it.id in cancelledIds })
        }
        Log.i(TAG, "Cancelled user scheduled jobs count=${userJobs.size}")
        return userJobs.size
    }

    /** All currently scheduled jobs, sorted by trigger time. */
    fun listJobs(): List<ScheduledJob> {
        val raw = prefs.getString(KEY_JOBS, "[]") ?: "[]"
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { parseJob(arr.getJSONObject(it)) }
                .sortedBy { it.triggerAtMs }
        }.getOrDefault(emptyList())
    }

    /** Persist the terminal result of an attempted job run for the task UI. */
    fun recordRunResult(
        jobId: String,
        outcome: ScheduledJobOutcome,
        durableTaskId: String? = null
    ) {
        val current = listJobs().toMutableList()
        val index = current.indexOfFirst { it.id == jobId }
        if (index >= 0) {
            current[index] = current[index].copy(
                lastRunAtMs = System.currentTimeMillis(),
                lastOutcome = outcome,
                lastDurableTaskId = durableTaskId ?: current[index].lastDurableTaskId
            )
            persistAllJobs(current)
        }
    }

    /** Query the WorkManager status for a job. */
    fun getJobStatus(jobId: String): WorkInfo.State? {
        val job = listJobs().firstOrNull { it.id == jobId } ?: return null
        return runCatching {
            job.workRequestId?.let { requestId ->
                workManager.getWorkInfoById(UUID.fromString(requestId)).get()?.state
            } ?: workManager.getWorkInfosByTag("airi_job_$jobId")
                .get()
                .firstOrNull()
                ?.state
        }.getOrNull()
    }

    // ── Persistence ────────────────────────────────────────────────────────────

    private fun uniqueWorkName(jobId: String): String = "airi_job_$jobId"

    private fun persistJob(job: ScheduledJob) {
        val current = listJobs().toMutableList()
        current.removeAll { it.id == job.id }
        current.add(job)
        persistAllJobs(current)
    }

    private fun removePersistedJob(jobId: String): Boolean {
        val current = listJobs().toMutableList()
        val before  = current.size
        current.removeAll { it.id == jobId }
        persistAllJobs(current)
        return current.size < before
    }

    private fun persistAllJobs(jobs: List<ScheduledJob>) {
        val arr = JSONArray()
        jobs.forEach { arr.put(jobToJson(it)) }
        prefs.edit().putString(KEY_JOBS, arr.toString()).apply()
    }

    private fun jobToJson(job: ScheduledJob): JSONObject = JSONObject().apply {
        put("id",           job.id)
        put("agent_id",     job.agentId)
        put("payload",      job.payload)
        put("label",        job.label)
        put("type",         job.type.name)
        put("trigger_at",   job.triggerAtMs)
        put("interval_ms",  job.intervalMs ?: JSONObject.NULL)
        put("requires_network", job.requiresNetwork)
        put("work_request_id", job.workRequestId ?: JSONObject.NULL)
        put("last_run_at", job.lastRunAtMs ?: JSONObject.NULL)
        put("last_outcome", job.lastOutcome.name)
        put("last_durable_task_id", job.lastDurableTaskId ?: JSONObject.NULL)
        put("project_id", job.projectId ?: JSONObject.NULL)
        put("owner_id", job.ownerId)
        put("privacy_level", job.privacyLevel)
    }

    private fun parseJob(json: JSONObject) = ScheduledJob(
        id          = json.getString("id"),
        agentId     = json.getString("agent_id"),
        payload     = json.getString("payload"),
        label       = json.getString("label"),
        type        = ScheduleType.valueOf(json.getString("type")),
        triggerAtMs = json.getLong("trigger_at"),
        intervalMs  = if (json.isNull("interval_ms")) null else json.getLong("interval_ms"),
        requiresNetwork = json.optBoolean("requires_network", false),
        workRequestId = json.optString("work_request_id").takeUnless { it.isBlank() || it == "null" },
        lastRunAtMs = if (json.isNull("last_run_at")) null else json.optLong("last_run_at").takeIf { it > 0L },
        lastOutcome = runCatching { ScheduledJobOutcome.valueOf(json.optString("last_outcome", "PENDING")) }
            .getOrDefault(ScheduledJobOutcome.PENDING),
        lastDurableTaskId = json.optString("last_durable_task_id")
            .takeUnless { it.isBlank() || it == "null" },
        projectId = json.optString("project_id").takeUnless { it.isBlank() || it == "null" },
        ownerId = json.optString("owner_id", "scheduled").ifBlank { "scheduled" },
        privacyLevel = json.optInt("privacy_level", 1).coerceIn(0, 2)
    )
}

// ── Domain types ───────────────────────────────────────────────────────────────

data class ScheduledJob(
    val id:          String,
    val agentId:     String,
    val payload:     String,
    val label:       String,
    val type:        ScheduleType,
    val triggerAtMs: Long,
    val intervalMs:  Long?,
    val requiresNetwork: Boolean = false,
    val workRequestId: String? = null,
    val lastRunAtMs: Long? = null,
    val lastOutcome: ScheduledJobOutcome = ScheduledJobOutcome.PENDING,
    /** Durable task created for the latest agent run; absent for maintenance jobs. */
    val lastDurableTaskId: String? = null,
    val projectId: String? = null,
    val ownerId: String = "scheduled",
    val privacyLevel: Int = 1
)

enum class ScheduleType { ONE_TIME, PERIODIC }
enum class ScheduledJobOutcome { PENDING, RETRYING, COMPLETED, FAILED }

