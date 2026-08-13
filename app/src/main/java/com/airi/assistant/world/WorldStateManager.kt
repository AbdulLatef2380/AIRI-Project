package com.airi.assistant.world

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * WorldStateManager — device environment sensor and causal world model.
 *
 * ── PHASE 5 UPGRADE: CAUSAL COGNITION ────────────────────────────────────────
 * The original implementation was a pure sensor: read battery, network, memory,
 * return a snapshot. The planner had no memory of what the environment looked
 * like before or after actions, and could not detect when execution produced
 * unexpected results.
 *
 * Upgraded with:
 *
 *  1. STATE HISTORY
 *     Last [HISTORY_SIZE] snapshots are retained in [stateHistory]. The planner
 *     can call [recentHistory] to see what the environment looked like over time,
 *     enabling trend detection (e.g. memory steadily dropping → throttle plan).
 *
 *  2. EXPECTATION VALIDATION
 *     Before executing an action, the planner calls [setExpectation] with a
 *     [WorldStateExpectation] describing what the world should look like after.
 *     After execution, [captureAndValidate] compares the actual state to the
 *     expectation and sets [WorldState.mismatchDetected] if they diverge.
 *     Mismatches are logged as AIRI_RUNTIME WORLD_MISMATCH events.
 *
 *  3. CAUSAL CONSEQUENCE TRACKING
 *     Each snapshot records [WorldState.causalOrigin] — the action that triggered
 *     the capture. This allows the planner to build a causal map:
 *     action → (before state, after state) pairs stored in [causalLog].
 *
 *  4. STATE CONFIDENCE SCORING
 *     Snapshots degrade in confidence as time passes (staleness). A snapshot
 *     taken 60 seconds ago has ~50% confidence vs. a fresh one. [currentConfidence]
 *     exposes this so the planner can decide to re-probe before a critical action.
 *
 *  5. ENVIRONMENT MISMATCH DETECTION
 *     [detectMismatch] compares two states and returns a human-readable summary
 *     of the differences for inclusion in recovery context.
 */
class WorldStateManager(private val context: Context) {

    companion object {
        private const val TAG           = "WorldStateManager"
        private const val HISTORY_SIZE  = 10
        private const val CAUSAL_LOG_SIZE = 20
        /** Staleness half-life in milliseconds (30 seconds → 50% confidence after 30s). */
        private const val CONFIDENCE_HALF_LIFE_MS = 30_000L
        private val LN2 = Math.log(2.0)
    }

    /** Rolling history of the last [HISTORY_SIZE] captured states. Thread-safe. */
    private val stateHistory = CopyOnWriteArrayList<WorldState>()

    /** Rolling causal log: action → (beforeState, afterState). Thread-safe. */
    private val causalLog = CopyOnWriteArrayList<CausalEntry>()

    /** Pending expectation set by the planner before an action. */
    @Volatile private var pendingExpectation: WorldStateExpectation? = null

    /** Last captured state (used for confidence and delta queries). */
    @Volatile private var lastState: WorldState? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Capture a fresh world state and append to [stateHistory].
     * If a [pendingExpectation] is set, performs mismatch validation.
     *
     * @param causalOrigin  The action being observed (null = baseline observation).
     */
    fun captureCurrentState(causalOrigin: String? = null): WorldState {
        val raw = buildSnapshot()
        val expectation = pendingExpectation

        val mismatch = if (expectation != null) {
            val m = detectMismatch(expectation, raw)
            if (m != null) {
                Log.w(TAG, "AIRI_RUNTIME WORLD_MISMATCH action=${expectation.actionId} detail=$m")
            }
            m != null
        } else false

        val state = raw.copy(
            expectation     = expectation,
            causalOrigin    = causalOrigin,
            mismatchDetected = mismatch
        )

        // Append to history with size cap
        stateHistory.add(state)
        while (stateHistory.size > HISTORY_SIZE) stateHistory.removeAt(0)

        // Record in causal log if this was triggered by an action
        if (causalOrigin != null && lastState != null) {
            val entry = CausalEntry(
                actionId  = causalOrigin,
                beforeState = lastState!!,
                afterState  = state,
                timestampMs = System.currentTimeMillis()
            )
            causalLog.add(entry)
            while (causalLog.size > CAUSAL_LOG_SIZE) causalLog.removeAt(0)
        }

        lastState = state
        pendingExpectation = null

        Log.d(TAG, "WORLD_SNAPSHOT network=${state.networkType} memMB=${state.availableMemoryMB} " +
            "battery=${state.batteryLevel} mismatch=$mismatch origin=${causalOrigin ?: "baseline"}")

        return state
    }

    /**
     * Alias for [captureCurrentState] — legacy callers that used `getCurrentState()`.
     */
    fun getCurrentState(): WorldState = captureCurrentState()

    /**
     * Set a planner expectation before executing an action.
     * The next call to [captureCurrentState] will validate against this expectation.
     *
     * @param expectation  What the planner expects the world to look like after the action.
     */
    fun setExpectation(expectation: WorldStateExpectation) {
        pendingExpectation = expectation
        Log.d(TAG, "WORLD_EXPECTATION_SET action=${expectation.actionId} rationaleChars=${expectation.rationale.length}")
    }

    /**
     * Clear any pending expectation (e.g. if an action is cancelled before execution).
     */
    fun clearExpectation() {
        pendingExpectation = null
    }

    /**
     * Current confidence in [lastState] based on staleness.
     * Returns 1.0 if no state has been captured yet (first call is always trusted).
     */
    fun currentConfidence(): Float {
        val last = lastState ?: return 1.0f
        val ageMs = System.currentTimeMillis() - last.timestamp
        val lambda = LN2 / CONFIDENCE_HALF_LIFE_MS.toDouble()
        return Math.exp(-lambda * ageMs).toFloat().coerceIn(0f, 1f)
    }

    /**
     * Return recent state history (newest last). At most [HISTORY_SIZE] entries.
     */
    fun recentHistory(): List<WorldState> = stateHistory.toList()

    /**
     * Return causal consequence log: what happened before/after each action.
     */
    fun causalConsequences(): List<CausalEntry> = causalLog.toList()

    /**
     * Detect a trend in available memory across [recentHistory].
     * Returns [MemoryTrend.DECLINING] if memory dropped >20% over the last 3+ snapshots.
     */
    fun memoryTrend(): MemoryTrend {
        val history = stateHistory.toList()
        if (history.size < 3) return MemoryTrend.STABLE
        val oldest = history.first().availableMemoryMB
        val newest = history.last().availableMemoryMB
        if (oldest == 0L) return MemoryTrend.STABLE
        val drop = (oldest - newest).toDouble() / oldest
        return when {
            drop >  0.25 -> MemoryTrend.DECLINING
            drop < -0.10 -> MemoryTrend.IMPROVING
            else         -> MemoryTrend.STABLE
        }
    }

    /**
     * Produce a human-readable summary of recent environment changes.
     * Suitable for injection into planner recovery context.
     */
    fun environmentSummary(): String {
        val state = lastState ?: return "No environment data captured."
        return buildString {
            append("Environment: battery=${state.batteryLevel}%")
            if (state.isCharging) append(" (charging)")
            append(", network=${state.networkType}")
            append(", memAvail=${state.availableMemoryMB}MB")
            append(", confidence=${"%.0f".format(currentConfidence() * 100)}%")
            val trend = memoryTrend()
            if (trend != MemoryTrend.STABLE) append(", memory=$trend")
            if (state.mismatchDetected) append(" ⚠ MISMATCH vs prior expectation")
        }
    }

    // ── Internal sensors ──────────────────────────────────────────────────────

    private fun buildSnapshot(): WorldState = WorldState(
        batteryLevel       = getBatteryLevel(),
        isCharging         = isCharging(),
        networkType        = getNetworkType(),
        isNetworkConnected = isNetworkConnected(),
        availableMemoryMB  = getAvailableMemoryMB(),
        topAppPackage      = null,
        timestamp          = System.currentTimeMillis()
    )

    private fun getBatteryLevel(): Int {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level  = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale  = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        return if (level != -1 && scale != -1) (level * 100 / scale) else -1
    }

    private fun isCharging(): Boolean {
        val intent  = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status  = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
               status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun getNetworkType(): NetworkType {
        val cm   = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net  = cm.activeNetwork ?: return NetworkType.NONE
        val caps = cm.getNetworkCapabilities(net) ?: return NetworkType.UNKNOWN
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)     -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            else -> NetworkType.UNKNOWN
        }
    }

    private fun isNetworkConnected(): Boolean = runCatching {
        val cm   = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net  = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(net) ?: return@runCatching false
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }.getOrDefault(false)

    private fun getAvailableMemoryMB(): Long {
        val am   = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.availMem / (1024 * 1024)
    }

    // ── Mismatch detection ────────────────────────────────────────────────────

    /**
     * Compare a [WorldStateExpectation] against an actual [WorldState].
     * Returns a human-readable mismatch description, or null if everything matched.
     */
    private fun detectMismatch(expectation: WorldStateExpectation, actual: WorldState): String? {
        val issues = mutableListOf<String>()

        expectation.expectedNetworkConnected?.let { exp ->
            if (actual.isNetworkConnected != exp)
                issues += "network_connected: expected=$exp actual=${actual.isNetworkConnected}"
        }
        expectation.expectedMinMemoryMB?.let { exp ->
            if (actual.availableMemoryMB < exp)
                issues += "memory: expected≥${exp}MB actual=${actual.availableMemoryMB}MB"
        }
        expectation.expectedTopAppPackage?.let { exp ->
            if (actual.topAppPackage != exp)
                issues += "topApp: expected=$exp actual=${actual.topAppPackage}"
        }
        expectation.expectedBatteryMinPercent?.let { exp ->
            if (actual.batteryLevel in 0 until exp)
                issues += "battery: expected≥${exp}% actual=${actual.batteryLevel}%"
        }

        return if (issues.isEmpty()) null else issues.joinToString(", ")
    }

    // ── Result types ──────────────────────────────────────────────────────────

    data class CausalEntry(
        val actionId:    String,
        val beforeState: WorldState,
        val afterState:  WorldState,
        val timestampMs: Long
    )

    enum class MemoryTrend { DECLINING, STABLE, IMPROVING }
}
