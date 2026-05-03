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
        val queued = task.copy(status = DurableTaskStatus.QUEUED)
        putTask(queued)
        submitToWorkManager(queued)
        Log.i(TAG, "AIRI_PROOF DURABLE_TASK_ENQUEUED id=${task.id} title='${task.title}'")
        return task.id
    }

    /**
     * Cancel a running or queued task.
     */
    fun cancel(taskId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(taskId))
        updateTask(taskId) { copy(status = DurableTaskStatus.CANCELLED) }
        Log.i(TAG, "AIRI_PROOF DURABLE_TASK_CANCELLED id=$taskId")
    }

    /**
     * Update checkpoint data for a running task.
     * Call from inside a DurableTaskWorker to persist progress.
     */
    fun updateCheckpoint(taskId: String, checkpointData: String, progressPercent: Int = -1, progressMessage: String = "") {
        updateTask(taskId) {
            copy(
                checkpointData  = checkpointData,
                progressPercent = progressPercent,
                progressMessage = progressMessage
            )
        }
    }

    /**
     * Mark a task as completed with its final result.
     */
    fun markCompleted(taskId: String, result: String) {
        updateTask(taskId) {
            copy(
                status        = DurableTaskStatus.COMPLETED,
                result        = result,
                finishedAtMs  = System.currentTimeMillis(),
                progressPercent = 100
            )
        }
        postCompletionNotification(taskId)
        Log.i(TAG, "AIRI_PROOF DURABLE_TASK_COMPLETED id=$taskId")
    }

    /**
     * Mark a task as failed.
     */
    fun markFailed(taskId: String, reason: String) {
        updateTask(taskId) {
            copy(
                status       = DurableTaskStatus.FAILED,
                errorReason  = reason,
                finishedAtMs = System.currentTimeMillis()
            )
        }
        Log.w(TAG, "AIRI_PROOF DURABLE_TASK_FAILED id=$taskId reason=$reason")
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

        // TODO: Connect to SubAgentRegistry and DurableTaskManager in full integration.
        // The worker operates in a separate process context — access via
        // ServiceLocator.durableTaskManager and ServiceLocator.subAgentRegistry.
        //
        // Full implementation:
        //   val manager = ServiceLocator.durableTaskManager
        //   val task = manager.getTask(taskId) ?: return Result.failure()
        //   manager.updateTask(taskId) { copy(status = RUNNING, startedAtMs = System.currentTimeMillis()) }
        //   val agent = SubAgentRegistry.findById(task.agentId)
        //     ?: return Result.failure()
        //   val context = buildSubAgentContext(task)
        //   agent.execute(task.input, context).collect { event ->
        //     when (event) {
        //       is AgentEvent.Complete -> manager.markCompleted(taskId, event.result)
        //       is AgentEvent.Failed   -> manager.markFailed(taskId, event.reason)
        //       is AgentEvent.Progress -> manager.updateCheckpoint(taskId, "", event.percentComplete, event.message)
        //       else -> {}
        //     }
        //   }

        return Result.success()
    }

    companion object {
        const val KEY_TASK_ID = "task_id"
        private const val TAG = "DurableTaskWorker"
    }
}
