package com.airi.assistant.execution.network

import android.util.Log
import com.airi.assistant.core.debug.EventSeverity
import com.airi.assistant.core.debug.RuntimeEventLog
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.PrivacyLevel
import com.airi.assistant.execution.prefs.ExecModePreferences

/**
 * Absolute network firewall for the execution layer.
 *
 * Called BEFORE any HTTP connection is opened by a cloud adapter.
 * This is the last enforcement line before bits leave the device.
 *
 * ## Guarantees
 *  - LOCAL_ONLY mode NEVER produces a non-BLOCKED result.
 *  - PrivacyLevel.MAXIMUM NEVER produces a non-BLOCKED result.
 *  - No internet permission NEVER produces a non-BLOCKED result.
 *
 * These three checks are **redundant with RoutingPolicy** by design.
 * Defense-in-depth: even if the router makes a wrong decision (e.g. due
 * to a race during mode-switch), NetworkGuard prevents the network call.
 *
 * ## Usage
 * ```kotlin
 * when (val decision = NetworkGuard.evaluate(prefs, targetUrl)) {
 *     is NetworkGuard.Decision.Allow  -> proceed()
 *     is NetworkGuard.Decision.Block  -> onError(decision.reason)
 * }
 * ```
 */
object NetworkGuard {

    private const val TAG = "AIRI_NetworkGuard"

    sealed class Decision {
        object Allow : Decision()
        data class Block(val reason: String, val errorType: BlockReason) : Decision()
    }

    enum class BlockReason {
        LOCAL_ONLY_MODE,
        MAXIMUM_PRIVACY,
        NO_INTERNET_PERMISSION,
        BUDGET_EXHAUSTED
    }

    /**
     * Evaluate whether a cloud request may proceed.
     *
     * @param prefs     Current execution preferences (mode, privacy, permission).
     * @param targetUrl The URL about to be dialled (for audit log only — never inspected
     *                  for routing decisions — this guard is URL-agnostic).
     * @return [Decision.Allow] if the request may proceed, [Decision.Block] otherwise.
     */
    fun evaluate(prefs: ExecModePreferences, targetUrl: String = ""): Decision {
        // ── Rule 1: LOCAL_ONLY absolute block ─────────────────────────────────
        if (prefs.effectiveMode == ExecutionMode.LOCAL_ONLY) {
            val reason = "LOCAL_ONLY mode — network request blocked"
            log(EventSeverity.WARN, reason)
            return Decision.Block(reason, BlockReason.LOCAL_ONLY_MODE)
        }

        // ── Rule 2: MAXIMUM privacy absolute block ────────────────────────────
        if (prefs.privacyLevel == PrivacyLevel.MAXIMUM) {
            val reason = "PrivacyLevel.MAXIMUM — cloud dispatch blocked by privacy policy"
            log(EventSeverity.WARN, reason)
            return Decision.Block(reason, BlockReason.MAXIMUM_PRIVACY)
        }

        // ── Rule 3: explicit internet permission gate ─────────────────────────
        if (!prefs.internetPermissionGranted) {
            val reason = "Internet permission not granted — network request blocked"
            log(EventSeverity.WARN, reason)
            return Decision.Block(reason, BlockReason.NO_INTERNET_PERMISSION)
        }

        // ── Rule 4: local budget is advisory, never a provider quota ───────────
        // Provider-side rate limits and billing remain authoritative. AIRI's
        // optional local counter is kept for visibility and hybrid preference
        // only; it must not silently block an explicitly selected cloud mode.
        if (prefs.isCloudBudgetExhausted) {
            Log.i(TAG, "NetworkGuard: local advisory budget reached " +
                "(${prefs.cloudTokensUsedToday}/${prefs.maxDailyCloudTokens}); allowing cloud")
        }

        // ── Allowed ────────────────────────────────────────────────────────────
        Log.d(TAG, "NetworkGuard: ALLOW mode=${prefs.effectiveMode.name}")
        return Decision.Allow
    }

    private fun log(severity: EventSeverity, reason: String) {
        Log.w(TAG, "NetworkGuard: BLOCK — $reason")
        RuntimeEventLog.post("NETWORK_GUARD", severity, reason)
    }
}
