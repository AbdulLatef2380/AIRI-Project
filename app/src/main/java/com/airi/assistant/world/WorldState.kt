package com.airi.assistant.world

/**
 * WorldState — A snapshot of device + environment state at a point in time.
 *
 * Extended (Phase 5) with causal model fields:
 *   - [expectation]       The planner's predicted state after an action.
 *   - [confidence]        How confident the system is that this snapshot is accurate.
 *   - [causalOrigin]      Which action produced this state (for consequence tracking).
 *   - [mismatchDetected]  True when actual state diverged from the prior expectation.
 */
data class WorldState(
    val batteryLevel:       Int,
    val isCharging:         Boolean,
    val networkType:        NetworkType,
    val isNetworkConnected: Boolean,
    val availableMemoryMB:  Long,
    val topAppPackage:      String?,
    val timestamp:          Long    = System.currentTimeMillis(),

    // ── Causal world model (Phase 5) ─────────────────────────────────────────
    /** Expected state the planner predicted before executing an action. Null on first snapshot. */
    val expectation:        WorldStateExpectation? = null,
    /** Confidence score 0.0–1.0 for this state snapshot (degrades with staleness). */
    val confidence:         Float   = 1.0f,
    /** The action that caused this state transition. Null for the initial observation. */
    val causalOrigin:       String? = null,
    /** True when this state diverges from the prior expectation. */
    val mismatchDetected:   Boolean = false
)

/**
 * A planner's expectation of what the world will look like after executing an action.
 * Compared against the actual [WorldState] after execution to detect mismatch.
 */
data class WorldStateExpectation(
    val actionId:                  String,
    val expectedNetworkConnected:  Boolean? = null,
    val expectedMinMemoryMB:       Long?    = null,
    val expectedTopAppPackage:     String?  = null,
    val expectedBatteryMinPercent: Int?     = null,
    val rationale:                 String   = ""
)

enum class NetworkType {
    WIFI, CELLULAR, NONE, UNKNOWN
}
