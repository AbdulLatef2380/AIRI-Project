package com.airi.assistant.core.runtime

import android.util.Log
import com.airi.assistant.agent.durable.DurableTask
import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.agent.tracker.GoalTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * RuntimeTaskManager — unified task lifecycle management.
 *
 * Bridges [AutonomousRuntimeManager] (session-level) with [DurableTaskManager]
 * (persistence-level) and [GoalTracker] (progress-level), presenting a single
 * clean API to the agent layer.
 *
 * ── TASK MODEL ───────────────────────────────────────────────────────────────
 *
 *   RuntimeTask: agentId + goal + coroutine scope
 *     ↓ persisted via DurableTaskManager (WorkManager)
 *     ↓ progress tracked via GoalTracker
 *     ↓ session managed via AutonomousRuntimeManager
 *
 * ── LIFECYCLE ────────────────────────────────────────────────────────────────
 *
 *   submit(task) → start → [RUNNING] → done/fail → [DONE/FAILED]
 *                             ↓
 *                          cancel() → [CANCELLED]
 *                             ↓
 *                          pause()  → [PAUSED]
 *                          resume() → [RUNNING]
 *
 * ── OBSERVABILITY ────────────────────────────────────────────────────────────
 *
 *   [tasks] StateFlow provides a live unified view combining DurableTaskManager
 *   and GoalTracker data. Collected by TaskDashboardScreen.
 */
class RuntimeTaskManager(
    private val durableTaskManager: DurableTaskManager,
    private val goalTracker:        GoalTracker,
    private val arm:                AutonomousRuntimeManager,
) {

    private val TAG   = "RuntimeTaskManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // ── Active job tracking ───────────────────────────────────────────────────

    private val activeJobs = ConcurrentHashMap<String, Job>()

    // ── Observable task view ──────────────────────────────────────────────────

    private val _tasks = MutableStateFlow<List<RuntimeTaskView>>(emptyList())
    val tasks: StateFlow<List<RuntimeTaskView>> = _tasks.asStateFlow()

    // ── Data classes ──────────────────────────────────────────────────────────

    enum class TaskStatus { PENDING, RUNNING, PAUSED, DONE, FAILED, CANCELLED }

    data class RuntimeTaskView(
        val id:           String,
        val goal:         String,
        val agentId:      String,
        val status:       TaskStatus,
        val progressPct:  Int,
        val milestones:   List<String>,
        val errorMessage: String?,
        val createdAtMs:  Long,
        val updatedAtMs:  Long,
    )

    // ── Initialization ────────────────────────────────────────────────────────

    init {
        // Merge GoalTracker flow into our unified task view
        scope.launch {
            goalTracker.goals.collect { goals ->
                _tasks.value = goals.map { g ->
                    RuntimeTaskView(
                        id           = g.id,
                        goal         = g.description,
                        agentId      = g.agentId,
                        status       = g.status.toTaskStatus(),
                        progressPct  = g.progressPct,
                        milestones   = g.milestones.map { it.text },
                        errorMessage = g.errorMessage,
                        createdAtMs  = g.createdAtMs,
                        updatedAtMs  = g.updatedAtMs,
                    )
                }
            }
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Submit a new task for execution.
     * Creates an [AutonomousRuntimeManager] session and persists via [DurableTaskManager].
     *
     * @return The new task ID.
     */
    suspend fun submit(
        goal:    String,
        agentId: String = "autonomous",
    ): String {
        val taskId    = UUID.randomUUID().toString()
        val sessionId = arm.startSession(goalText = goal, agentId = agentId)

        // Persist with WorkManager for boot-survivability
        runCatching {
            durableTaskManager.enqueue(DurableTask(
                id          = taskId,
                title       = goal.take(80),
                description = goal,
                agentId     = agentId,
            ))
        }.onFailure { Log.w(TAG, "DurableTask enqueue failed: ${it.message}") }

        Log.i(TAG, "TASK_SUBMITTED taskId=$taskId sessionId=$sessionId goal='${goal.take(60)}'")
        return taskId
    }

    /**
     * Cancel a running task.
     */
    suspend fun cancel(taskId: String) {
        activeJobs[taskId]?.cancel()
        activeJobs.remove(taskId)
        goalTracker.cancel(taskId)
        arm.cancelSession(taskId)
        Log.i(TAG, "TASK_CANCELLED taskId=$taskId")
    }

    /**
     * Cancel all active tasks.
     */
    suspend fun cancelAll() {
        activeJobs.keys.toList().forEach { cancel(it) }
    }

    /**
     * Remove completed/failed tasks older than [olderThanMs].
     */
    suspend fun pruneCompleted(olderThanMs: Long = 24 * 60 * 60 * 1000L) {
        goalTracker.pruneTerminal(olderThanMs)
        Log.d(TAG, "TASK_PRUNE completed tasks older than ${olderThanMs / 3600_000}h removed")
    }

    fun activeCount(): Int = _tasks.value.count { it.status == TaskStatus.RUNNING }

    // ── Mapping ───────────────────────────────────────────────────────────────

    private fun GoalTracker.GoalStatus.toTaskStatus() = when (this) {
        GoalTracker.GoalStatus.PENDING     -> TaskStatus.PENDING
        GoalTracker.GoalStatus.IN_PROGRESS -> TaskStatus.RUNNING
        GoalTracker.GoalStatus.DONE        -> TaskStatus.DONE
        GoalTracker.GoalStatus.FAILED      -> TaskStatus.FAILED
        GoalTracker.GoalStatus.CANCELLED   -> TaskStatus.CANCELLED
        GoalTracker.GoalStatus.PAUSED      -> TaskStatus.PAUSED
    }
}
