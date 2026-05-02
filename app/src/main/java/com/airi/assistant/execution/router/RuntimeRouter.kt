package com.airi.assistant.execution.router

import android.content.Context
import android.util.Log
import com.airi.assistant.core.debug.EventSeverity
import com.airi.assistant.core.debug.RuntimeEventLog
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.backend.CloudBackend
import com.airi.assistant.execution.backend.LocalLlamaBackend
import com.airi.assistant.execution.backend.RuntimeBackend
import com.airi.assistant.execution.prefs.ExecModePreferences

/**
 * The routing brain of the Hybrid Execution layer.
 *
 * [route] is the single entry point for routing decisions. It:
 *  1. Reads a fresh [DeviceSignals] snapshot (thermal, RAM, network, battery)
 *  2. Delegates the decision to [RoutingPolicy.select] (pure-function rules)
 *  3. Logs the decision to [RuntimeEventLog] for the diagnostics timeline
 *  4. Returns a [RoutingDecision] that the orchestrator uses to execute
 *
 * ## Threading
 * [route] dispatches [DeviceSignals.read] to [Dispatchers.Default] and is
 * itself safe to call from any coroutine context. The policy evaluation is
 * synchronous and completes in microseconds.
 *
 * ## Lifecycle
 * [RuntimeRouter] is a singleton-per-ViewModel. It holds references to the
 * two backend instances (local + cloud) and the shared preferences — none of
 * which own coroutine scopes, so the router itself is leak-free.
 *
 * ## No blocking on Main
 * The router never posts UI state. Callers are responsible for surfacing the
 * routing decision to the UI (via ViewModel StateFlows).
 */
class RuntimeRouter(
    val localBackend:  LocalLlamaBackend,
    val cloudBackend:  CloudBackend,
    val prefs:         ExecModePreferences
) {

    /**
     * Result of a routing evaluation.
     *
     * @param primary    Backend to attempt first.
     * @param fallbacks  Remaining backends to try if [primary] fails, in order.
     * @param rationale  Human-readable explanation — logged to event timeline.
     * @param signals    Device snapshot at the moment of routing (for diagnostics).
     */
    data class RoutingDecision(
        val primary:   RuntimeBackend,
        val fallbacks: List<RuntimeBackend>,
        val rationale: String,
        val signals:   DeviceSignals
    ) {
        val hasFallback: Boolean get() = fallbacks.isNotEmpty()

        /** All backends in priority order (primary first). */
        val allBackends: List<RuntimeBackend>
            get() = listOf(primary) + fallbacks
    }

    /**
     * Route an [ExecutionRequest] to the best available backend.
     *
     * Reads device signals, evaluates routing policy, logs the decision,
     * and returns a [RoutingDecision]. Never throws — failures in signal
     * reading are surfaced as safe defaults (no network, moderate pressure).
     *
     * @param request  The execution request to route.
     * @param context  Android context for device signal reading.
     */
    suspend fun route(request: ExecutionRequest, context: Context): RoutingDecision {
        // Read fresh device signals (dispatches to Default internally).
        val signals = runCatching { DeviceSignals.read(context) }
            .getOrElse { e ->
                Log.w(TAG, "DeviceSignals.read failed: ${e.message}")
                DeviceSignals(
                    thermalLevel     = com.airi.assistant.core.debug.ThermalLevel.NONE,
                    thermalRaw       = 0,
                    availRamMb       = 1024L,
                    totalRamMb       = 2048L,
                    isLowMemory      = false,
                    networkAvailable = false,
                    networkType      = DeviceSignals.NetworkType.NONE,
                    batteryLevel     = 100,
                    isCharging       = false,
                    cpuCores         = 4
                )
            }

        // Evaluate routing policy — pure function, no I/O.
        val selection = RoutingPolicy.select(
            request = request,
            signals = signals,
            prefs   = prefs,
            local   = localBackend,
            cloud   = cloudBackend
        )

        // Log to the diagnostics timeline.
        val logMsg = "Route → ${selection.primary.id} " +
            "(${selection.backends.size} candidate(s)) · ${selection.rationale}"
        Log.i(TAG, logMsg)
        RuntimeEventLog.post(
            subsystem = "ROUTER",
            severity  = EventSeverity.INFO,
            reason    = logMsg
        )

        return RoutingDecision(
            primary   = selection.primary,
            fallbacks = selection.backends.drop(1),
            rationale = selection.rationale,
            signals   = signals
        )
    }

    companion object {
        private const val TAG = "AIRI_RuntimeRouter"
    }
}
