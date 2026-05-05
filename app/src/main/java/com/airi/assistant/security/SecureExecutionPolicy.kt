package com.airi.assistant.security

import android.util.Log
import com.airi.assistant.domain.logging.LoggingService

/**
 * SecureExecutionPolicy — composite policy that governs whether an agent
 * execution request should be permitted.
 *
 * ── DECISION FACTORS ─────────────────────────────────────────────────────
 *
 *   1. [CommandAllowlist]       — binary on allowed list?
 *   2. [ScopedPermissionRegistry] — agent holds required permission?
 *   3. [RiskLevel]              — is the risk level acceptable for this context?
 *   4. [rateLimit]              — has the agent exceeded the per-minute rate?
 *
 * ── POLICY COMPOSITION ───────────────────────────────────────────────────
 *
 *   All factors are checked in order. The first rejection short-circuits
 *   evaluation. A [PolicyDecision.Deny] carries a [DenyReason] that the
 *   agent runtime can surface to the user.
 *
 * ── AUDIT TRAIL ──────────────────────────────────────────────────────────
 *
 *   Every evaluation (allow or deny) emits an `AIRI_PROOF POLICY_EVALUATED`
 *   logcat line for runtime traceability.
 */
class SecureExecutionPolicy(
    private val permissionRegistry: ScopedPermissionRegistry,
    private val allowedRiskLevel:   RiskLevel = RiskLevel.MEDIUM,
    private val maxCallsPerMinute:  Int        = 60
) {

    private val TAG = "SecureExecutionPolicy"

    // Per-agent call counters (window: last 60 seconds).
    // ConcurrentHashMap so concurrent evaluateTool() calls from different
    // Dispatchers.IO threads do not race on the outer map. The inner ArrayDeque
    // is accessed only under the per-entry computeIfAbsent lock pattern.
    private val callWindows = java.util.concurrent.ConcurrentHashMap<String, ArrayDeque<Long>>()

    // ── Evaluate ──────────────────────────────────────────────────────────────

    /**
     * Evaluate whether [agentId] may execute a shell command [command].
     *
     * @param agentId   The calling agent.
     * @param command   Full command string (e.g. "ls -la /tmp").
     * @param riskLevel Caller-declared risk level for this operation.
     */
    fun evaluateCommand(
        agentId:   String,
        command:   String,
        riskLevel: RiskLevel = RiskLevel.LOW
    ): PolicyDecision {
        val decision = doEvaluate(agentId, command, riskLevel, requireShellPermission = true)
        log(agentId, command, riskLevel, decision)
        return decision
    }

    /**
     * Evaluate a generic tool call (not a shell command).
     *
     * @param agentId   The calling agent.
     * @param toolName  Tool identifier (e.g. "file_read", "web_search").
     * @param riskLevel Caller-declared risk level.
     */
    fun evaluateTool(
        agentId:   String,
        toolName:  String,
        riskLevel: RiskLevel = RiskLevel.LOW
    ): PolicyDecision {
        val decision = doEvaluate(agentId, toolName, riskLevel, requireShellPermission = false)
        log(agentId, toolName, riskLevel, decision)
        return decision
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun doEvaluate(
        agentId:              String,
        target:               String,
        riskLevel:            RiskLevel,
        requireShellPermission: Boolean
    ): PolicyDecision {
        // 1. Rate limiting
        if (!checkRate(agentId)) {
            return PolicyDecision.Deny(
                DenyReason.RATE_LIMITED,
                "Agent $agentId exceeded $maxCallsPerMinute calls/min"
            )
        }

        // 2. Allowlist check (only for shell commands)
        if (requireShellPermission) {
            val check = CommandAllowlist.checkCommand(target)
            if (check is CommandAllowlist.CheckResult.Denied) {
                return PolicyDecision.Deny(DenyReason.NOT_ALLOWLISTED, check.reason)
            }
        }

        // 3. Risk level gate
        if (riskLevel > allowedRiskLevel) {
            return PolicyDecision.Deny(
                DenyReason.RISK_TOO_HIGH,
                "Operation risk=$riskLevel exceeds allowed=$allowedRiskLevel"
            )
        }

        // 4. Permission check (non-throwing)
        val requiredPerm = shellPermissionFor(riskLevel)
        if (!permissionRegistry.check(agentId, requiredPerm)) {
            return PolicyDecision.Deny(
                DenyReason.PERMISSION_DENIED,
                "Agent $agentId missing permission $requiredPerm for risk=$riskLevel"
            )
        }

        recordCall(agentId)
        return PolicyDecision.Allow
    }

    private fun shellPermissionFor(risk: RiskLevel): ScopedPermissionRegistry.AgentPermission =
        when (risk) {
            RiskLevel.LOW    -> ScopedPermissionRegistry.AgentPermission.READ_FILES
            RiskLevel.MEDIUM -> ScopedPermissionRegistry.AgentPermission.WRITE_FILES
            RiskLevel.HIGH   -> ScopedPermissionRegistry.AgentPermission.SPAWN_SUBAGENT
        }

    private fun checkRate(agentId: String): Boolean {
        val now     = System.currentTimeMillis()
        val window  = callWindows.getOrPut(agentId) { ArrayDeque() }
        // Evict entries older than 60 seconds
        while (window.isNotEmpty() && (now - window.first()) > 60_000L) {
            window.removeFirst()
        }
        return window.size < maxCallsPerMinute
    }

    private fun recordCall(agentId: String) {
        callWindows.getOrPut(agentId) { ArrayDeque() }.addLast(System.currentTimeMillis())
    }

    private fun log(agentId: String, target: String, risk: RiskLevel, decision: PolicyDecision) {
        val outcome = if (decision is PolicyDecision.Allow) "ALLOW" else "DENY"
        LoggingService.info(TAG, "AIRI_PROOF POLICY_EVALUATED agent=$agentId target='${target.take(60)}' risk=$risk outcome=$outcome")
        if (decision is PolicyDecision.Deny) {
            Log.w(TAG, "POLICY_DENY reason=${decision.reason} msg=${decision.message}")
        }
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    enum class RiskLevel { LOW, MEDIUM, HIGH }

    sealed class PolicyDecision {
        object Allow : PolicyDecision()
        data class Deny(val reason: DenyReason, val message: String) : PolicyDecision()
    }

    enum class DenyReason {
        NOT_ALLOWLISTED,
        PERMISSION_DENIED,
        RISK_TOO_HIGH,
        RATE_LIMITED
    }
}
// NOTE: No custom compareTo needed — Kotlin enums implement Comparable<E> natively.
// RiskLevel.HIGH > RiskLevel.MEDIUM is true via ordinal ordering provided by the JVM.
