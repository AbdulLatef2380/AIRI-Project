package com.airi.assistant.agent.durable

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import com.airi.assistant.R

/**
 * Manager for durable long-running tasks that survive app closure.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * PERSISTENCE MODEL
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Tasks are persisted as JSON in the app's private files directory:
 *   {filesDir}/durable_tasks.json
 *
 *   This avoids Room migration complexity. The file is written atomically
 *   (write-to-temp, rename) to prevent corruption on process kill.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * EXECUTION MODEL
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Each task is submitted to WorkManager as a OneTimeWorkRequest with a
 *   unique work name (the task ID). WorkManager handles:
 *     - Retry on failure (backoff policy)
 *     - Constraint satisfaction (network, charging)
 *     - Persistence across process kills
 *
 *   The DurableTaskWorker reads the task from the JSON file, executes it,
 *   and writes the result back.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CHECKPOINT SEMANTICS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   Sub-agents may call [updateCheckpoint] to write intermediate state.
 *   On retry, DurableTaskWorker passes the checkpoint to the agent so it
 *   can resume from where it left off.
 */
class DurableTaskManager(private val context: Context) {

    private val TAG = "DurableTaskManager"
    private val gson = Gson()
    private val taskFile = File(context.filesDir, "durable_tasks.json")

    // ── In-memory cache (source of truth for UI) ──────────────────────────────

    private val taskCache = ConcurrentHashMap<String, DurableTask>()
    private val _tasks = MutableStateFlow<List<DurableTask>>(emptyList())
    val tasks: StateFlow<List<DurableTask>> = _tasks.asStateFlow()

    init {
        loadFromDisk()
        createNotificationChannel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Queue a new durable task for execution.
     * Returns the task ID (UUID) for tracking.
     */
    fun enqueue(task: DurableTask): String {
        val queued = task.copy(
            status = DurableTaskStatus.QUEUED,
            updatedAtMs = System.currentTimeMillis()
        )
        putTask(queued)
        submitToWorkManager(queued)
        Log.i(TAG, "AIRI DURABLE_TASK_ENQUEUED id=${task.id} title='${task.title}'")
        return task.id
    }

    /**
     * Registers a task executed by the foreground orchestrator. Unlike [enqueue],
     * this never creates a second WorkManager execution for the same user intent.
     */
    fun registerInProcess(task: DurableTask): String {
        if (taskCache.containsKey(task.id)) return task.id
        val now = System.currentTimeMillis()
        putTask(
            task.copy(updatedAtMs = now).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.TASK_REGISTERED,
                    summary = "Task registered for foreground execution",
                    recordedAtMs = now
                )
            )
        )
        Log.i(TAG, "AIRI DURABLE_TASK_REGISTERED_IN_PROCESS id=${task.id}")
        return task.id
    }

    /** Starts or resumes a durable execution run and records its active plan step. */
    fun beginRun(taskId: String, runId: String = taskId, stepId: String? = null) {
        updateTask(taskId) {
            beginRun(runId = runId, stepId = stepId).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.RUN_STARTED,
                    summary = "Execution run started",
                    runId = runId,
                    stepId = stepId
                )
            )
        }
    }

    /** Persists a safe execution checkpoint and the current plan step. */
    fun updateExecutionStep(
        taskId: String,
        stepId: String?,
        checkpointData: String = "",
        progressPercent: Int = -1,
        progressMessage: String = ""
    ) {
        updateTask(taskId) {
            updateStep(
                stepId = stepId,
                checkpointData = checkpointData,
                progressPercent = progressPercent,
                progressMessage = progressMessage
            ).appendTimeline(
                TaskTimelineEvent(
                    type = if (progressPercent == 0) TaskTimelineEventType.STEP_STARTED else TaskTimelineEventType.STEP_PROGRESS,
                    summary = safeTimelineText(progressMessage.ifBlank { "Execution progress updated" }),
                    runId = currentRunId,
                    stepId = stepId
                )
            )
        }
    }

    /** Marks an individual plan step as complete while keeping the task running. */
    fun markStepCompleted(taskId: String, stepId: String) {
        updateTask(taskId) {
            completeStep(stepId).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.STEP_COMPLETED,
                    summary = "Plan step completed",
                    runId = currentRunId,
                    stepId = stepId
                )
            )
        }
    }

    /** Records a failed plan step before the task-level recovery policy decides what to do. */
    fun markStepFailed(taskId: String, stepId: String, reason: String) {
        updateTask(taskId) {
            failStep(stepId, reason).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.STEP_FAILED,
                    summary = "Plan step failed",
                    detail = safeTimelineText(reason),
                    runId = currentRunId,
                    stepId = stepId
                )
            )
        }
    }

    /**
     * Cancel a running or queued task.
     */
    fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
        updateTask(taskId) {
            cancel().appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.TASK_CANCELLED,
                    summary = "Task cancelled by user or runtime",
                    runId = currentRunId,
                    stepId = currentStepId
                )
            )
        }
        Log.i(TAG, "AIRI DURABLE_TASK_CANCELLED id=$taskId")
    }

    /**
     * Update checkpoint data for a running task.
     * Call from inside a DurableTaskWorker to persist progress.
     */
    fun updateCheckpoint(taskId: String, checkpointData: String, progressPercent: Int = -1, progressMessage: String = "") {
        updateExecutionStep(
            taskId = taskId,
            stepId = getTask(taskId)?.currentStepId,
            checkpointData = checkpointData,
            progressPercent = progressPercent,
            progressMessage = progressMessage
        )
    }

    /**
     * Mark a task as completed with its final result.
     */
    fun markCompleted(taskId: String, result: String) {
        updateTask(taskId) {
            complete(result).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.TASK_COMPLETED,
                    summary = "Task completed",
                    detail = safeTimelineText(result),
                    runId = currentRunId,
                    stepId = currentStepId
                )
            )
        }
        postCompletionNotification(taskId)
        Log.i(TAG, "AIRI DURABLE_TASK_COMPLETED id=$taskId")
    }

    /**
     * Mark a task as failed.
     */
    fun markFailed(taskId: String, reason: String) {
        updateTask(taskId) {
            fail(reason).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.TASK_FAILED,
                    summary = "Task failed",
                    detail = safeTimelineText(reason),
                    runId = currentRunId,
                    stepId = currentStepId
                )
            )
        }
        Log.w(TAG, "AIRI DURABLE_TASK_FAILED id=$taskId reason=$reason")
    }

    /** Creates a task-owned approval request with an explicit expiry. */
    fun requestApproval(
        taskId: String,
        action: String,
        description: String,
        riskLevel: String,
        expiresInMs: Long = DEFAULT_APPROVAL_EXPIRY_MS,
        runId: String? = null,
        stepId: String? = null
    ): TaskApproval? {
        val task = getTask(taskId) ?: return null
        val now = System.currentTimeMillis()
        val approval = TaskApproval(
            id = java.util.UUID.randomUUID().toString().take(12),
            action = safeTimelineText(action),
            description = safeTimelineText(description),
            riskLevel = riskLevel,
            requestedAtMs = now,
            expiresAtMs = now + expiresInMs.coerceIn(MIN_APPROVAL_EXPIRY_MS, MAX_APPROVAL_EXPIRY_MS),
            runId = runId ?: task.currentRunId,
            stepId = stepId ?: task.currentStepId
        )
        updateTask(taskId) {
            requestApproval(approval).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.APPROVAL_REQUESTED,
                    summary = "Approval required: ${approval.action}",
                    detail = approval.description,
                    runId = approval.runId,
                    stepId = approval.stepId,
                    recordedAtMs = now
                )
            )
        }
        return approval
    }

    /** Decides a pending approval. Expired approvals cannot be granted. */
    fun decideApproval(
        approvalId: String,
        status: TaskApprovalStatus,
        scope: ApprovalGrantScope = ApprovalGrantScope.ONCE,
        reason: String = ""
    ): Boolean {
        val task = taskCache.values.firstOrNull { candidate ->
            candidate.approvals.any { it.id == approvalId }
        } ?: return false
        val current = task.approvals.first { it.id == approvalId }
        val now = System.currentTimeMillis()
        val resolvedStatus = if (current.status != TaskApprovalStatus.PENDING) {
            return false
        } else if (current.expiresAtMs <= now) {
            TaskApprovalStatus.EXPIRED
        } else {
            status
        }
        updateTask(task.id) {
            decideApproval(
                approvalId = approvalId,
                status = resolvedStatus,
                scope = scope,
                reason = safeTimelineText(reason),
                nowMs = now
            ).appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.APPROVAL_DECIDED,
                    summary = "Approval ${resolvedStatus.name.lowercase()}: ${current.action}",
                    detail = safeTimelineText(reason),
                    runId = current.runId,
                    stepId = current.stepId,
                    recordedAtMs = now
                )
            )
        }
        return resolvedStatus == status
    }

    /** Marks stale pending approvals expired and returns their count. */
    fun expireApprovals(nowMs: Long = System.currentTimeMillis()): Int {
        var expired = 0
        taskCache.values.forEach { task ->
            task.approvals
                .filter { it.status == TaskApprovalStatus.PENDING && it.expiresAtMs <= nowMs }
                .forEach { approval ->
                    if (decideApproval(approval.id, TaskApprovalStatus.EXPIRED, reason = "Approval expired")) {
                        expired++
                    }
                }
        }
        return expired
    }

    fun pendingApprovals(): List<Pair<DurableTask, TaskApproval>> {
        expireApprovals()
        return taskCache.values.flatMap { task ->
            task.approvals
                .filter { it.status == TaskApprovalStatus.PENDING }
                .map { approval -> task to approval }
        }.sortedBy { (_, approval) -> approval.requestedAtMs }
    }

    /** Appends a sanitised task-owned event suitable for replay. */
    fun recordTimeline(
        taskId: String,
        type: TaskTimelineEventType,
        summary: String,
        detail: String = "",
        runId: String? = null,
        stepId: String? = null
    ) {
        updateTask(taskId) {
            appendTimeline(
                TaskTimelineEvent(
                    type = type,
                    summary = safeTimelineText(summary),
                    detail = safeTimelineText(detail),
                    runId = runId ?: currentRunId,
                    stepId = stepId ?: currentStepId
                )
            )
        }
    }

    /**
     * Exports bounded, content-free task progress metadata for an explicitly
     * enabled continuity transport. This does not expose task input, result,
     * checkpoint, approval detail, timeline text, diagnostics, or artifacts.
     */
    fun continuitySnapshots(limit: Int = MAX_CONTINUITY_SNAPSHOTS): List<TaskContinuitySnapshot> =
        taskCache.values
            .sortedByDescending { it.updatedAtMs }
            .take(limit.coerceIn(1, MAX_CONTINUITY_SNAPSHOTS))
            .map(TaskContinuitySnapshot::from)

    /**
     * Applies a newer remote progress snapshot only to a task already owned by
     * this device. A remote RUNNING state is represented locally as PAUSED so
     * the receiver never starts duplicate execution without an explicit resume.
     */
    fun mergeContinuitySnapshot(snapshot: TaskContinuitySnapshot): ContinuityMergeResult {
        if (snapshot.schemaVersion != TaskContinuitySnapshot.SCHEMA_VERSION) {
            return ContinuityMergeResult.UnsupportedSchema
        }
        val local = getTask(snapshot.taskId) ?: return ContinuityMergeResult.UnknownTask
        if (snapshot.updatedAtMs <= local.updatedAtMs) return ContinuityMergeResult.LocalNewer
        if (local.status == DurableTaskStatus.RUNNING) return ContinuityMergeResult.LocalExecutionActive

        val remoteSteps = snapshot.plan.associateBy { it.id }
        val remoteStatus = if (snapshot.status == DurableTaskStatus.RUNNING) {
            DurableTaskStatus.PAUSED
        } else {
            snapshot.status
        }
        val merged = local.copy(
            status = remoteStatus,
            updatedAtMs = snapshot.updatedAtMs,
            currentRunId = snapshot.currentRunId,
            currentStepId = snapshot.currentStepId,
            progressPercent = snapshot.progressPercent,
            executionNode = snapshot.executionNode,
            plan = local.plan.map { localStep ->
                remoteSteps[localStep.id]?.let { remoteStep ->
                    localStep.copy(
                        status = remoteStep.status,
                        startedAtMs = remoteStep.startedAtMs,
                        completedAtMs = remoteStep.completedAtMs
                    )
                } ?: localStep
            }
        ).appendTimeline(
            TaskTimelineEvent(
                type = TaskTimelineEventType.CONTINUITY_MERGED,
                summary = "Newer task progress received from another device",
                runId = snapshot.currentRunId,
                stepId = snapshot.currentStepId,
                recordedAtMs = snapshot.updatedAtMs
            )
        )
        putTask(merged)
        return ContinuityMergeResult.Merged(remoteWasRunning = snapshot.status == DurableTaskStatus.RUNNING)
    }

    /** Get a task by ID. */
    fun getTask(taskId: String): DurableTask? = taskCache[taskId]

    /** All non-terminal tasks (QUEUED, RUNNING, PAUSED). */
    fun activeTasks(): List<DurableTask> =
        taskCache.values.filter { !it.isTerminal }

    /** All completed tasks (most recent first). */
    fun completedTasks(): List<DurableTask> =
        taskCache.values.filter { it.isTerminal }
            .sortedByDescending { it.finishedAtMs }

    // ─────────────────────────────────────────────────────────────────────────
    // Internal
    // ─────────────────────────────────────────────────────────────────────────

    private fun safeTimelineText(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim().take(MAX_TIMELINE_TEXT_CHARS)
        return if (SENSITIVE_TIMELINE_PATTERN.containsMatchIn(normalized)) "Sensitive detail redacted" else normalized
    }

    private fun putTask(task: DurableTask) {
        taskCache[task.id] = task
        emitUpdate()
        saveToDisk()
    }

    private fun updateTask(taskId: String, transform: DurableTask.() -> DurableTask) {
        val existing = taskCache[taskId] ?: return
        val updated  = existing.transform()
        taskCache[taskId] = updated
        emitUpdate()
        saveToDisk()
    }

    private fun emitUpdate() {
        _tasks.value = taskCache.values
            .sortedByDescending { it.queuedAtMs }
            .toList()
    }

    private fun submitToWorkManager(task: DurableTask) {
        val constraints = Constraints.Builder().apply {
            if (task.requiresNetwork)   setRequiredNetworkType(NetworkType.CONNECTED)
            if (task.requiresCharging)  setRequiresCharging(true)
        }.build()

        val inputData = Data.Builder()
            .putString(DurableTaskWorker.KEY_TASK_ID, task.id)
            .build()

        val request = OneTimeWorkRequestBuilder<DurableTaskWorker>()
            .setConstraints(constraints)
            .setInputData(inputData)
            .addTag(task.agentId)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                workName(task.id),
                androidx.work.ExistingWorkPolicy.KEEP,
                request
            )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // File persistence
    // ─────────────────────────────────────────────────────────────────────────

    private fun saveToDisk() {
        runCatching {
            val json = gson.toJson(taskCache.values.toList())
            // Atomic write: write-to-temp → rename
            val tmp = File(taskFile.parent, "${taskFile.name}.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            tmp.renameTo(taskFile)
        }.onFailure {
            Log.e(TAG, "Failed to persist tasks to disk: ${it.message}")
        }
    }

    private fun loadFromDisk() {
        runCatching {
            if (!taskFile.exists()) return
            val json = taskFile.readText(Charsets.UTF_8)
            val type = object : TypeToken<List<DurableTask>>() {}.type
            val list: List<DurableTask> = gson.fromJson(json, type) ?: emptyList()
            list.forEach { taskCache[it.id] = it }
            emitUpdate()
            Log.i(TAG, "Loaded ${list.size} durable tasks from disk")
        }.onFailure {
            Log.e(TAG, "Failed to load tasks from disk: ${it.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Notifications
    // ─────────────────────────────────────────────────────────────────────────

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "AIRI Background Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows progress of long-running AIRI tasks"
                setShowBadge(true)
            }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    private fun postCompletionNotification(taskId: String) {
        val task = taskCache[taskId] ?: return
        if (!task.showNotification) return

        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("AIRI task complete")
            .setContentText(task.title)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        nm.notify(taskId.hashCode(), notification)
    }

    companion object {
        private const val CHANNEL_ID = "airi_tasks_channel"
         const val MAX_TIMELINE_TEXT_CHARS = 480
        const val MAX_CONTINUITY_SNAPSHOTS = 250
        private const val MIN_APPROVAL_EXPIRY_MS = 10_000L
        private const val DEFAULT_APPROVAL_EXPIRY_MS = 5 * 60_000L
        private const val MAX_APPROVAL_EXPIRY_MS = 24 * 60 * 60_000L
        private val SENSITIVE_TIMELINE_PATTERN = Regex(
            "(?i)(api[_ -]?key|password|secret|authorization|bearer\\s+[a-z0-9._-]+)"
        )
        fun workName(taskId: String) = "durable_task_$taskId"
    }
}

/**
 * WorkManager worker that executes a [DurableTask].
 *
 * Reads the task from [DurableTaskManager], invokes the appropriate
 * sub-agent via [SubAgentRegistry], and writes the result back.
 */
class DurableTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        Log.i(TAG, "DurableTaskWorker starting taskId=$taskId attempt=$runAttemptCount")

        val manager = com.airi.assistant.core.ServiceLocator.durableTaskManager
        val task = manager.getTask(taskId) ?: run {
            Log.w(TAG, "Task $taskId not found in store — aborting")
            return Result.failure()
        }

        manager.beginRun(
            taskId = taskId,
            runId = task.currentRunId ?: taskId,
            stepId = task.currentStepId
        )
        manager.updateCheckpoint(taskId, task.checkpointData, 0, "Starting…")

        return runCatching {
            val registry = com.airi.assistant.agent.subagent.SubAgentRegistry
            val agent = registry.findById(task.agentId)
                ?: registry.findById("research_agent")
                ?: run {
                    manager.markFailed(taskId, "No agent found for id=${task.agentId}")
                    return Result.failure()
                }

            val context = com.airi.assistant.agent.subagent.SubAgentContext(
                sessionId         = task.projectId ?: taskId,
                userId            = task.ownerId,
                projectId         = task.projectId,
                worldState        = emptyMap(),
                grantedPermissions = emptyList(),
                parentTaskId      = taskId,
                nestingDepth      = 0,
                dependencyResults = task.checkpointData.takeIf { it.isNotBlank() }
                    ?.let { mapOf("checkpoint" to it) }
                    ?: emptyMap()
            )

            var finalResult = ""
            agent.execute(task.input, context).collect { event ->
                when (event) {
                    is com.airi.assistant.agent.subagent.AgentEvent.Complete ->
                        finalResult = event.result
                    is com.airi.assistant.agent.subagent.AgentEvent.Failed ->
                        manager.markFailed(taskId, event.reason)
                    is com.airi.assistant.agent.subagent.AgentEvent.Progress ->
                        manager.updateCheckpoint(taskId, "", event.percentComplete, event.message)
                    else -> Unit
                }
            }

            if (finalResult.isNotBlank()) {
                manager.markCompleted(taskId, finalResult)
                Log.i(TAG, "AIRI DURABLE_TASK_DONE taskId=$taskId")
            }
            Result.success()
        }.getOrElse { e ->
            Log.e(TAG, "DurableTaskWorker failed taskId=$taskId: ${e.message}", e)
            com.airi.assistant.core.ServiceLocator.crashReporter.reportDurableTaskCrash(
                taskId  = taskId,
                agentId = task.agentId,
                throwable = e
            )
            manager.markFailed(taskId, e.message ?: "unknown error")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        const val KEY_TASK_ID  = "task_id"
        private const val TAG  = "DurableTaskWorker"
        private const val MAX_RETRIES = 2
    }
}
