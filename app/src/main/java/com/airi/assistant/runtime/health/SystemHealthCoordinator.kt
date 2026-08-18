package com.airi.assistant.runtime.health

import android.content.Context
import android.util.Log
import com.airi.assistant.ai.PerformanceMode
import com.airi.assistant.execution.HybridOrchestrator
import com.airi.assistant.runtime.thermal.ThermalProfiler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * SystemHealthCoordinator — adaptive throttling bridge.
 *
 * ── , ────────────────────────────────────────────────────────
 * Previously [ThermalProfiler] collected accurate thermal and battery signals
 * but they were never acted upon: the data was only logged to logcat. The
 * agent could continue at full context-window size even when the device was
 * thermally throttled, accelerating overheating.
 *
 * This coordinator closes the feedback loop:
 *   1. Subscribes to [ThermalProfiler.throttleLevel] via a StateFlow.
 *   2. On each REDUCE or EMERGENCY level change, restricts the context window
 *      budget by calling the [onThrottleChange] callback so callers can adapt
 *      the [HybridOrchestrator] request or the [PerformanceMode] chosen by
 *      [ModelController].
 *   3. On return to NONE, restores full performance.
 *
 * ── Integration pattern ────────────────────────────────────────────────────
 * Construct one instance from [com.airi.assistant.core.ServiceLocator] and
 * call [start]. The ServiceLocator holds [ThermalProfiler] and starts it
 * independently; this coordinator only observes its StateFlow output.
 *
 *   ServiceLocator.systemHealthCoordinator.start()
 *
 * The coordinator is decoupled from HybridOrchestrator to avoid circular
 * ServiceLocator dependencies. The callback pattern lets callers adapt any
 * subsystem (ModelController, HybridOrchestrator, PromptBudgetLedger, etc.)
 * to the thermal signal.
 *
 * ── Throttle policy ────────────────────────────────────────────────────────
 * | ThermalProfiler.ThrottleLevel | Recommended action                     |
 * |:-------------------------------|:---------------------------------------|
 * | NONE                           | Full context window; normal operation  |
 * | REDUCE                         | Cut max context tokens by 50%          |
 * | EMERGENCY                      | Abort in-flight inference; ban new     |
 * |                                | inference until level drops to NONE    |
 *
 * ── Thread safety ──────────────────────────────────────────────────────────
 * All StateFlow observations run on [Dispatchers.Default]. [onThrottleChange]
 * is called from that dispatcher; implementations should be thread-safe.
 */
class SystemHealthCoordinator(
    private val context:         Context,
    private val thermalProfiler: ThermalProfiler,
    /** Called whenever the throttle level changes. The [PerformanceMode] argument
     *  is the recommended new mode; null means "stop inference immediately". */
    private val onThrottleChange: (ThrottleAction) -> Unit
) {

    /**
     * Describes what the receiving component should do in response to a
     * throttle-level change.
     */
    sealed class ThrottleAction {
        /** System is healthy; use full performance. */
        object FullPerformance : ThrottleAction()

        /**
         * Moderate thermal load detected. Reduce context window.
         * @param contextReductionFactor fraction of the full context budget to keep (0.0–1.0).
         *        E.g. 0.5 means "use 50% of the normal token budget".
         */
        data class ReduceLoad(val contextReductionFactor: Float) : ThrottleAction()

        /** Critical thermal / battery state. Abort in-flight inference. */
        object EmergencyStop : ThrottleAction()
    }

    private val scope:       CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var observerJob: Job?           = null

    val throttleLevel: StateFlow<ThermalProfiler.ThrottleLevel> =
        thermalProfiler.throttleLevel

    // ── Lifecycle ──────────────────────────────────────────────────────────────

    /**
     * Begin observing the ThermalProfiler. Must be called after [ThermalProfiler.start].
     * Safe to call multiple times — only one observer runs at a time.
     */
    fun start() {
        if (observerJob?.isActive == true) return
        Log.i(TAG, "AIRI SYSTEM_HEALTH_COORDINATOR_STARTED")

        observerJob = scope.launch {
            // distinctUntilChanged() is intentionally absent here.
            // StateFlow already guarantees that collectors only receive emissions
            // when the value actually changes — it is distinct by contract.
            // Since kotlinx-coroutines ≥1.7.0, applying distinctUntilChanged() to
            // a StateFlow is a compile error ("Operator Fusion" rule). The behaviour
            // is identical without it.
            thermalProfiler.throttleLevel
                .collect { level ->
                    val action = levelToAction(level)
                    Log.i(TAG,
                        "AIRI THERMAL_THROTTLE_ACTION level=$level action=${action::class.simpleName}")
                    onThrottleChange(action)
                }
        }
    }

    /** Stop observing. The ThermalProfiler itself keeps running. */
    fun stop() {
        observerJob?.cancel()
        observerJob = null
        Log.i(TAG, "AIRI SYSTEM_HEALTH_COORDINATOR_STOPPED")
    }

    // ── Snapshot API ──────────────────────────────────────────────────────────

    /** Current recommended action without subscribing to the flow. */
    val currentAction: ThrottleAction
        get() = levelToAction(thermalProfiler.throttleLevel.value)

    /**
     * True when the device is in EMERGENCY throttle — callers should refuse to
     * start new inference until this returns false.
     */
    val isEmergency: Boolean
        get() = thermalProfiler.throttleLevel.value == ThermalProfiler.ThrottleLevel.EMERGENCY

    /**
     * Fraction of the full context token budget that should be used right now.
     * 1.0 = full budget, 0.5 = halved, 0.0 = no inference allowed.
     */
    val contextBudgetFactor: Float
        get() = when (thermalProfiler.throttleLevel.value) {
            ThermalProfiler.ThrottleLevel.NONE      -> 1.0f
            ThermalProfiler.ThrottleLevel.REDUCE    -> 0.5f
            ThermalProfiler.ThrottleLevel.EMERGENCY -> 0.0f
        }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun levelToAction(level: ThermalProfiler.ThrottleLevel): ThrottleAction =
        when (level) {
            ThermalProfiler.ThrottleLevel.NONE      -> ThrottleAction.FullPerformance
            ThermalProfiler.ThrottleLevel.REDUCE    -> ThrottleAction.ReduceLoad(0.5f)
            ThermalProfiler.ThrottleLevel.EMERGENCY -> ThrottleAction.EmergencyStop
        }

    private companion object {
        const val TAG = "AIRI_SystemHealthCoordinator"
    }
}
