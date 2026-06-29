package com.airi.assistant.runtime.health

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ThermalSignal — process-wide thermal throttle state for prompt budget scaling.
 *
 * ── Phase 2, Task 11: SystemHealthCoordinator Feedback Loop ───────────────────
 * Previously [SystemHealthCoordinator] emitted [SystemHealthCoordinator.ThrottleAction]
 * but nothing acted on it — the context-window budget was never reduced under
 * thermal load. This singleton closes the open control loop:
 *
 *   [SystemHealthCoordinator] → sets [ThermalSignal] → read by [PromptBudgetLedger]
 *
 * [PromptBudgetLedger.forBudget] pre-allocates a THERMAL_RESERVE equal to the
 * fraction of the context window that must not be used, determined by
 * [contextBudgetFactor]. Under normal conditions the reserve is zero; under
 * REDUCE throttle it is 50% of nCtx; under EMERGENCY it is 100% (no inference).
 *
 * ── Thread safety ─────────────────────────────────────────────────────────────
 * Both fields are [AtomicReference] / [AtomicBoolean] — lock-free reads from
 * any thread. [update] is called from [Dispatchers.Default] inside
 * [ServiceLocator.systemHealthCoordinator.onThrottleChange].
 *
 * ── Lifecycle ─────────────────────────────────────────────────────────────────
 * No initialization required. Default state is NONE (factor = 1.0, not emergency).
 * [ServiceLocator] sets the value via [update] when thermal state changes.
 */
object ThermalSignal {

    private val _contextBudgetFactor = AtomicReference(1.0f)
    private val _isEmergency          = AtomicBoolean(false)

    /**
     * Fraction of the full context token budget that may be used right now.
     *  - 1.0 → full budget (NONE throttle level)
     *  - 0.5 → halved (REDUCE throttle level)
     *  - 0.0 → no inference allowed (EMERGENCY throttle level)
     */
    val contextBudgetFactor: Float get() = _contextBudgetFactor.get()

    /**
     * True when the device is in EMERGENCY thermal state. Callers that want
     * a quick boolean guard without reading [contextBudgetFactor] use this.
     */
    val isEmergency: Boolean get() = _isEmergency.get()

    /**
     * Update the signal. Called exclusively by [com.airi.assistant.core.ServiceLocator]
     * inside the [com.airi.assistant.runtime.health.SystemHealthCoordinator.onThrottleChange]
     * callback. Do not call from application code.
     *
     * @param factor    New context budget fraction (clamped to [0.0, 1.0]).
     * @param emergency Whether the device is in EMERGENCY throttle state.
     */
    internal fun update(factor: Float, emergency: Boolean) {
        _contextBudgetFactor.set(factor.coerceIn(0.0f, 1.0f))
        _isEmergency.set(emergency)
    }
}
