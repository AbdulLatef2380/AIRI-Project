package com.airi.assistant.agent.tracker

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * GoalTracker — persistent, lifecycle-safe goal and progress tracking.
 *
 * Every autonomous task has a [TrackedGoal] that progresses through:
 *   PENDING → IN_PROGRESS → (DONE | FAILED | CANCELLED | PAUSED)
 *
 * Goals survive process death via [SharedPreferences] + JSON serialization.
 * The [goals] StateFlow drives the TaskDashboard UI without polling.
 *
 * ── DESIGN PRINCIPLES ────────────────────────────────────────────────────────
 *
 *   - One GoalTracker per AutonomousRuntimeManager session.
 *   - Goals have a [parentGoalId] for nested sub-goal tracking.
 *   - Progress is 0–100 (percentage); milestones are string breadcrumbs.
 *   - All mutations go through the [mutex] — safe for concurrent agent calls.
 *
 * ── INTEGRATION ──────────────────────────────────────────────────────────────
 *
 *   ServiceLocator exposes the singleton. AgentPlanner creates/updates goals.
 *   TaskDashboardScreen subscribes to [goals].
 */
class GoalTracker(context: Context) {

    private val TAG   = "GoalTracker"
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val mutex = Mutex()

    // ── State ─────────────────────────────────────────────────────────────────

    private val _goals = MutableStateFlow<List<TrackedGoal>>(emptyList())
    val goals: StateFlow<List<TrackedGoal>> = _goals.asStateFlow()

    // ── Data model ────────────────────────────────────────────────────────────

    enum class GoalStatus { PENDING, IN_PROGRESS, DONE, FAILED, CANCELLED, PAUSED }

    data class GoalMilestone(
        val text:        String,
        val timestampMs: Long = System.currentTimeMillis(),
    )

    data class TrackedGoal(
        val id:             String,
        val description:    String,
        val parentGoalId:   String?            = null,
        val status:         GoalStatus         = GoalStatus.PENDING,
        val progressPct:    Int                = 0,
        val milestones:     List<GoalMilestone> = emptyList(),
        val createdAtMs:    Long               = System.currentTimeMillis(),
        val updatedAtMs:    Long               = System.currentTimeMillis(),
        val errorMessage:   String?            = null,
        val agentId:        String             = "",
        val estimatedSteps: Int                = 0,
        val completedSteps: Int                = 0,
    ) {
        val isTerminal: Boolean get() = status in setOf(GoalStatus.DONE, GoalStatus.FAILED, GoalStatus.CANCELLED)
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    init {
        val loaded = loadFromPrefs()
        _goals.value = loaded
        Log.i(TAG, "GoalTracker initialized with ${loaded.size} persisted goals")
    }

    // ── CRUD API ──────────────────────────────────────────────────────────────

    suspend fun createGoal(
        description:    String,
        agentId:        String  = "",
        parentGoalId:   String? = null,
        estimatedSteps: Int     = 0,
    ): TrackedGoal = mutex.withLock {
        val goal = TrackedGoal(
            id             = UUID.randomUUID().toString(),
            description    = description,
            agentId        = agentId,
            parentGoalId   = parentGoalId,
            estimatedSteps = estimatedSteps,
        )
        val updated = _goals.value + goal
        _goals.value = updated
        saveToPrefs(updated)
        Log.i(TAG, "GOAL_CREATED id=${goal.id} desc='${description.take(60)}'")
        goal
    }

    suspend fun updateProgress(
        goalId:    String,
        pct:       Int,
        milestone: String? = null,
    ) = mutex.withLock {
        val updated = _goals.value.map { g ->
            if (g.id != goalId) g else {
                val newMilestones = if (milestone != null)
                    g.milestones + GoalMilestone(milestone) else g.milestones
                g.copy(
                    progressPct    = pct.coerceIn(0, 100),
                    status         = if (g.status == GoalStatus.PENDING) GoalStatus.IN_PROGRESS else g.status,
                    milestones     = newMilestones,
                    updatedAtMs    = System.currentTimeMillis(),
                )
            }
        }
        _goals.value = updated
        saveToPrefs(updated)
    }

    suspend fun advanceStep(goalId: String, milestone: String? = null) = mutex.withLock {
        val updated = _goals.value.map { g ->
            if (g.id != goalId) g else {
                val steps    = g.completedSteps + 1
                val pct      = if (g.estimatedSteps > 0) (steps * 100 / g.estimatedSteps).coerceAtMost(95) else g.progressPct
                val newMiles = if (milestone != null) g.milestones + GoalMilestone(milestone) else g.milestones
                g.copy(completedSteps = steps, progressPct = pct, milestones = newMiles, updatedAtMs = System.currentTimeMillis())
            }
        }
        _goals.value = updated
        saveToPrefs(updated)
    }

    suspend fun markDone(goalId: String, milestone: String? = null) = mutex.withLock {
        val updated = _goals.value.map { g ->
            if (g.id != goalId) g else {
                val newMiles = if (milestone != null) g.milestones + GoalMilestone(milestone) else g.milestones
                g.copy(status = GoalStatus.DONE, progressPct = 100, milestones = newMiles, updatedAtMs = System.currentTimeMillis())
            }
        }
        _goals.value = updated
        saveToPrefs(updated)
        Log.i(TAG, "GOAL_DONE id=$goalId")
    }

    suspend fun markFailed(goalId: String, reason: String) = mutex.withLock {
        val updated = _goals.value.map { g ->
            if (g.id != goalId) g else
                g.copy(status = GoalStatus.FAILED, errorMessage = reason, updatedAtMs = System.currentTimeMillis())
        }
        _goals.value = updated
        saveToPrefs(updated)
        Log.w(TAG, "GOAL_FAILED id=$goalId reason='${reason.take(80)}'")
    }

    suspend fun cancel(goalId: String) = mutex.withLock {
        val updated = _goals.value.map { g ->
            if (g.id != goalId) g else g.copy(status = GoalStatus.CANCELLED, updatedAtMs = System.currentTimeMillis())
        }
        _goals.value = updated
        saveToPrefs(updated)
    }

    fun getGoal(goalId: String): TrackedGoal? = _goals.value.firstOrNull { it.id == goalId }

    fun activeGoals(): List<TrackedGoal> = _goals.value.filter { !it.isTerminal }

    suspend fun pruneTerminal(olderThanMs: Long = 24 * 60 * 60 * 1000L) = mutex.withLock {
        val cutoff  = System.currentTimeMillis() - olderThanMs
        val pruned  = _goals.value.filter { !it.isTerminal || it.updatedAtMs > cutoff }
        _goals.value = pruned
        saveToPrefs(pruned)
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private fun saveToPrefs(goals: List<TrackedGoal>) {
        runCatching {
            val arr = JSONArray()
            goals.takeLast(MAX_PERSISTED).forEach { g ->
                arr.put(JSONObject().apply {
                    put("id", g.id); put("description", g.description)
                    put("parentGoalId", g.parentGoalId ?: "")
                    put("status", g.status.name); put("progressPct", g.progressPct)
                    put("createdAtMs", g.createdAtMs); put("updatedAtMs", g.updatedAtMs)
                    put("errorMessage", g.errorMessage ?: "")
                    put("agentId", g.agentId)
                    put("estimatedSteps", g.estimatedSteps); put("completedSteps", g.completedSteps)
                    val miles = JSONArray(); g.milestones.forEach { m -> miles.put(m.text) }
                    put("milestones", miles)
                })
            }
            prefs.edit().putString(KEY_GOALS, arr.toString()).apply()
        }.onFailure { Log.e(TAG, "saveToPrefs failed: ${it.message}") }
    }

    private fun loadFromPrefs(): List<TrackedGoal> {
        val json = prefs.getString(KEY_GOALS, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val obj = arr.getJSONObject(i)
                    val milesArr = obj.optJSONArray("milestones")
                    val miles = if (milesArr != null) (0 until milesArr.length()).map { GoalMilestone(milesArr.getString(it)) } else emptyList()
                    TrackedGoal(
                        id             = obj.getString("id"),
                        description    = obj.getString("description"),
                        parentGoalId   = obj.optString("parentGoalId").ifEmpty { null },
                        status         = GoalStatus.valueOf(obj.getString("status")),
                        progressPct    = obj.optInt("progressPct", 0),
                        createdAtMs    = obj.optLong("createdAtMs", System.currentTimeMillis()),
                        updatedAtMs    = obj.optLong("updatedAtMs", System.currentTimeMillis()),
                        errorMessage   = obj.optString("errorMessage").ifEmpty { null },
                        agentId        = obj.optString("agentId"),
                        estimatedSteps = obj.optInt("estimatedSteps", 0),
                        completedSteps = obj.optInt("completedSteps", 0),
                        milestones     = miles,
                    )
                }.getOrNull()
            }
        }.getOrDefault(emptyList())
    }

    companion object {
        private const val PREFS_NAME   = "airi_goal_tracker"
        private const val KEY_GOALS    = "goals_v1"
        private const val MAX_PERSISTED = 50
    }
}
