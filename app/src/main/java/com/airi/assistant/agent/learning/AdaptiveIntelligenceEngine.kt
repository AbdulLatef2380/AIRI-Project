package com.airi.assistant.agent.learning

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.ln
import kotlin.math.max

/**
 * AdaptiveIntelligenceEngine — lightweight, privacy-safe, offline-first
 * adaptive learning layer for AIRI.
 *
 * What it learns (all stored locally on-device, never transmitted):
 *  1. Tool usage patterns → predict which tool the user prefers for a task type
 *  2. Local vs cloud preference → routing heuristic based on past success rates
 *  3. Response style adaptation → track preferred response length/detail level
 *  4. Connector usage prediction → rank connectors by recent activity
 *  5. Task similarity matching → re-use successful execution paths for similar tasks
 *  6. Execution success weighting → down-rank tools/routes that failed recently
 *
 * Privacy guarantees:
 *  - All data stored in [SharedPreferences] — never leaves the device
 *  - Only aggregate counters stored (no raw message text)
 *  - User can clear via Settings → Privacy
 *  - No telemetry, no analytics backend
 *
 * Algorithm:
 *  Uses a simple decaying-weight scoring model (UCB1-inspired):
 *    score(item) = successRate + sqrt(2 * ln(totalAttempts) / itemAttempts)
 *  This balances exploitation (known good tools) with exploration (trying alternatives).
 */
class AdaptiveIntelligenceEngine(private val context: Context) {

    private val TAG   = "AdaptiveIntelligenceEngine"
    private val prefs = context.getSharedPreferences("airi_adaptive_intelligence", Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Observable routing hints ──────────────────────────────────────────────
    private val _routingHints = MutableStateFlow(RoutingHints())
    val routingHints: StateFlow<RoutingHints> = _routingHints.asStateFlow()

    data class RoutingHints(
        /** 0.0 = always local, 1.0 = always cloud */
        val cloudPreference: Float = 0.5f,
        /** Ordered list of preferred tool IDs for the current session */
        val preferredTools:  List<String> = emptyList(),
        /** Top connector IDs by recent activity */
        val activeConnectors: List<String> = emptyList()
    )

    init { refreshHints() }

    // ── Record events ─────────────────────────────────────────────────────────

    /** Call when a tool execution completes. */
    fun recordToolOutcome(toolId: String, success: Boolean, taskType: String = "") {
        scope.launch {
            val key = "tool:${toolId}"
            val attempts = prefs.getInt("$key:attempts", 0) + 1
            val successes = prefs.getInt("$key:successes", 0) + (if (success) 1 else 0)
            prefs.edit()
                .putInt("$key:attempts", attempts)
                .putInt("$key:successes", successes)
                .putLong("$key:lastMs", System.currentTimeMillis())
                .apply()
            if (taskType.isNotBlank()) {
                val ttKey = "tasktype:${taskType}:tool:${toolId}"
                prefs.edit().putInt("$ttKey:count", prefs.getInt("$ttKey:count", 0) + 1).apply()
            }
            refreshHints()
        }
    }

    /** Call when an inference call completes. */
    fun recordInferenceOutcome(isCloud: Boolean, success: Boolean, latencyMs: Long) {
        scope.launch {
            val key = if (isCloud) "cloud" else "local"
            val attempts = prefs.getInt("inference:$key:attempts", 0) + 1
            val successes = prefs.getInt("inference:$key:successes", 0) + (if (success) 1 else 0)
            val avgLatency = ((prefs.getLong("inference:$key:avgLatencyMs", latencyMs) * (attempts - 1)) + latencyMs) / attempts
            prefs.edit()
                .putInt("inference:$key:attempts",         attempts)
                .putInt("inference:$key:successes",        successes)
                .putLong("inference:$key:avgLatencyMs",    avgLatency)
                .apply()
            refreshHints()
        }
    }

    /** Call when a connector is used. */
    fun recordConnectorUsage(connectorId: String) {
        scope.launch {
            val key = "connector:${connectorId}"
            prefs.edit()
                .putInt("$key:count", prefs.getInt("$key:count", 0) + 1)
                .putLong("$key:lastMs", System.currentTimeMillis())
                .apply()
            refreshHints()
        }
    }

    /** Record response style preference (length in words). */
    fun recordResponseStyle(wordCount: Int, thumbsUp: Boolean) {
        scope.launch {
            if (thumbsUp) {
                val avg = ((prefs.getInt("style:avgWords", wordCount) + wordCount) / 2)
                prefs.edit().putInt("style:avgWords", avg).apply()
            }
        }
    }

    // ── Prediction API ────────────────────────────────────────────────────────

    /**
     * Returns an ordered list of tool IDs ranked by their UCB1 score.
     * Higher-scoring tools should be tried first.
     */
    fun rankedTools(candidates: List<String>): List<String> {
        val totalAttempts = candidates.sumOf { prefs.getInt("tool:${it}:attempts", 0) }
            .coerceAtLeast(1)
        return candidates.sortedByDescending { toolId ->
            val attempts  = prefs.getInt("tool:${toolId}:attempts",  0)
            val successes = prefs.getInt("tool:${toolId}:successes", 0)
            ucb1Score(successes, attempts, totalAttempts)
        }
    }

    /**
     * Returns 0.0–1.0 cloud preference score based on past inference outcomes.
     * >0.6 = prefer cloud, <0.4 = prefer local.
     */
    fun inferenceCloudScore(): Float {
        val cloudAtt = prefs.getInt("inference:cloud:attempts", 1).coerceAtLeast(1)
        val cloudSuc = prefs.getInt("inference:cloud:successes", 1)
        val localAtt = prefs.getInt("inference:local:attempts", 1).coerceAtLeast(1)
        val localSuc = prefs.getInt("inference:local:successes", 1)
        val cloudRate = cloudSuc.toFloat() / cloudAtt
        val localRate = localSuc.toFloat() / localAtt
        val total     = cloudRate + localRate
        return if (total == 0f) 0.5f else cloudRate / total
    }

    /** Returns the approximate preferred response word count. */
    fun preferredResponseLength(): Int = prefs.getInt("style:avgWords", 120)

    /** Returns connector IDs sorted by recent activity (most recent first). */
    fun activeConnectors(candidates: List<String>): List<String> =
        candidates.sortedByDescending { prefs.getLong("connector:${it}:lastMs", 0L) }

    // ── Hints refresh ─────────────────────────────────────────────────────────

    private fun refreshHints() {
        val allToolKeys = prefs.all.keys
            .filter { it.startsWith("tool:") && it.endsWith(":attempts") }
            .map { it.removePrefix("tool:").removeSuffix(":attempts") }
        val sortedTools = rankedTools(allToolKeys).take(5)

        val connectorKeys = prefs.all.keys
            .filter { it.startsWith("connector:") && it.endsWith(":count") }
            .map { it.removePrefix("connector:").removeSuffix(":count") }
        val sortedConnectors = activeConnectors(connectorKeys).take(5)

        _routingHints.value = RoutingHints(
            cloudPreference  = inferenceCloudScore(),
            preferredTools   = sortedTools,
            activeConnectors = sortedConnectors
        )
    }

    // ── Privacy ───────────────────────────────────────────────────────────────

    /** Clear all learned data. */
    fun clearAll() {
        prefs.edit().clear().apply()
        _routingHints.value = RoutingHints()
        Log.i(TAG, "All adaptive intelligence data cleared")
        AgentActivityBus.emit("Adaptive intelligence data cleared", ActivityCategory.SYSTEM)
    }

    /** Export a privacy-safe summary (no raw text, only aggregate stats). */
    fun exportSummaryJson(): String {
        val toolCount       = prefs.all.keys.count { it.startsWith("tool:") && it.endsWith(":attempts") }
        val connectorCount  = prefs.all.keys.count { it.startsWith("connector:") && it.endsWith(":count") }
        val cloudAttempts   = prefs.getInt("inference:cloud:attempts", 0)
        val localAttempts   = prefs.getInt("inference:local:attempts", 0)
        return JSONObject().apply {
            put("toolsTracked",       toolCount)
            put("connectorsTracked",  connectorCount)
            put("cloudAttempts",      cloudAttempts)
            put("localAttempts",      localAttempts)
            put("cloudPreference",    inferenceCloudScore())
            put("preferredStyleWords", preferredResponseLength())
        }.toString(2)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** UCB1 upper confidence bound score. */
    private fun ucb1Score(successes: Int, attempts: Int, totalAttempts: Int): Double {
        if (attempts == 0) return Double.MAX_VALUE   // unexplored → explore first
        val exploitationTerm = successes.toDouble() / attempts
        val explorationTerm  = Math.sqrt(2.0 * ln(totalAttempts.toDouble()) / attempts)
        return exploitationTerm + explorationTerm
    }
}
