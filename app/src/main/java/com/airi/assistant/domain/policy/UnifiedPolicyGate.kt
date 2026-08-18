package com.airi.assistant.domain.policy

import android.util.Log
import com.airi.assistant.agent.learning.SkillOutcomeScorer
import com.airi.assistant.domain.monetization.ActionType
import com.airi.assistant.domain.monetization.ConsumeResult
import com.airi.assistant.domain.monetization.CreditMeteringEngine
import com.airi.assistant.domain.permission.PermissionService

// ─────────────────────────────────────────────────────────────────────────────
// UnifiedPolicyGate — single enforcement point for every tool/connector call
//
// No tool or connector may execute without passing through [check].
// It atomically enforces:
//   1. Android runtime permissions (via PermissionService)
//   2. Daily credit budget (via CreditMeteringEngine)
//   3. Tool-level skill policy (via SkillOutcomeScorer) — PREFERRED tools cost
//      fewer credits; BLOCKED tools are hard-denied
//
// Usage:
//   val decision = UnifiedPolicyGate.check(
//       context   = ctx,
//       toolName  = "github_push",
//       action    = ActionType.SKILL_USE,
//       requiredPermissions = listOf(Manifest.permission.INTERNET)
//   )
//   if (decision !is PolicyDecision.Allow) { /* abort */ }
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "UnifiedPolicyGate"

// ── Decisions ─────────────────────────────────────────────────────────────────

sealed class PolicyDecision {
    /** All checks passed. Proceed with execution. */
    data class Allow(
        val creditRemaining: Int,
        val toolPolicy:      SkillOutcomeScorer.ToolPolicy
    ) : PolicyDecision()

    /** Blocked by one or more reasons. */
    data class Deny(
        val reason:    DenyReason,
        val userMessage: String
    ) : PolicyDecision()

    enum class DenyReason {
        MISSING_PERMISSION,
        CREDIT_EXHAUSTED,
        TOOL_BLOCKED,
        TOOL_AVOIDED      // soft deny — caller may try an alternative
    }
}

// ── Gate ──────────────────────────────────────────────────────────────────────

object UnifiedPolicyGate {

    /**
     * Check credit + permission + skill-policy before any tool execution.
     *
     * @param creditEngine       Singleton [CreditMeteringEngine].
     * @param permissionService  Singleton [PermissionService].
     * @param outcomeScorer      Singleton [SkillOutcomeScorer] (nullable — skipped if null).
     * @param toolName           The name of the tool/skill being invoked.
     * @param action             The [ActionType] credit weight to consume.
     * @param requiredPermissions Android permission strings the tool needs.
     * @param dryRun             If true, check but do NOT consume credits.
     */
    fun check(
        creditEngine:        CreditMeteringEngine,
        permissionService:   PermissionService,
        outcomeScorer:       SkillOutcomeScorer?,
        toolName:            String,
        action:              ActionType,
        requiredPermissions: List<String> = emptyList(),
        dryRun:              Boolean      = false
    ): PolicyDecision {

        // 1. ── Skill policy check (fast, no side-effect) ──────────────────────
        if (outcomeScorer != null) {
            val policy = outcomeScorer.getPolicy(toolName)
            when (policy) {
                SkillOutcomeScorer.ToolPolicy.BLOCKED -> {
                    Log.w(TAG, "AIRI POLICY_GATE_DENY tool=$toolName reason=TOOL_BLOCKED")
                    return PolicyDecision.Deny(
                        reason      = PolicyDecision.DenyReason.TOOL_BLOCKED,
                        userMessage = "Tool '$toolName' has been automatically blocked due to repeated failures. " +
                                      "Please try an alternative approach."
                    )
                }
                SkillOutcomeScorer.ToolPolicy.AVOID -> {
                    Log.w(TAG, "AIRI POLICY_GATE_SOFT_DENY tool=$toolName reason=TOOL_AVOIDED")
                    return PolicyDecision.Deny(
                        reason      = PolicyDecision.DenyReason.TOOL_AVOIDED,
                        userMessage = "Tool '$toolName' has a low reliability score. Consider a different tool."
                    )
                }
                else -> {}
            }
        }

        // 2. ── Permission check ───────────────────────────────────────────────
        val missing = requiredPermissions.filter { !permissionService.hasPermission(it) }
        if (missing.isNotEmpty()) {
            Log.w(TAG, "AIRI POLICY_GATE_DENY tool=$toolName reason=MISSING_PERMISSION perms=$missing")
            return PolicyDecision.Deny(
                reason      = PolicyDecision.DenyReason.MISSING_PERMISSION,
                userMessage = "Missing Android permissions for '$toolName': ${missing.joinToString()}. " +
                              "Please grant access in Settings → Permissions."
            )
        }

        // 3. ── Credit check ───────────────────────────────────────────────────
        val creditResult = if (dryRun) {
            val snap = creditEngine.snapshot()
            if (snap.remaining > 0) ConsumeResult.Allowed(action, 0, snap.dailyTotal, snap.budget, snap.remaining)
            else ConsumeResult.Denied(action, snap.dailyTotal, snap.budget, 1)
        } else {
            creditEngine.consume(action)
        }

        if (creditResult is ConsumeResult.Denied) {
            Log.w(TAG, "AIRI POLICY_GATE_DENY tool=$toolName reason=CREDIT_EXHAUSTED")
            return PolicyDecision.Deny(
                reason      = PolicyDecision.DenyReason.CREDIT_EXHAUSTED,
                userMessage = creditResult.userMessage
            )
        }

        val allowed = creditResult as ConsumeResult.Allowed
        Log.d(TAG, "AIRI POLICY_GATE_ALLOW tool=$toolName action=${action.name} " +
            "creditsUsed=${allowed.creditsUsed} remaining=${allowed.remaining}")

        return PolicyDecision.Allow(
            creditRemaining = allowed.remaining,
            toolPolicy      = outcomeScorer?.getPolicy(toolName)
                              ?: SkillOutcomeScorer.ToolPolicy.NORMAL
        )
    }

    /**
     * Convenience wrapper for callers that only care about pass/fail.
     * Returns true if execution is allowed (credits consumed).
     */
    fun isAllowed(
        creditEngine:        CreditMeteringEngine,
        permissionService:   PermissionService,
        outcomeScorer:       SkillOutcomeScorer?,
        toolName:            String,
        action:              ActionType,
        requiredPermissions: List<String> = emptyList()
    ): Boolean = check(creditEngine, permissionService, outcomeScorer, toolName, action, requiredPermissions) is PolicyDecision.Allow

    /**
     * Dry-run: check without consuming credits.
     */
    fun canExecute(
        creditEngine:        CreditMeteringEngine,
        permissionService:   PermissionService,
        outcomeScorer:       SkillOutcomeScorer?,
        toolName:            String,
        action:              ActionType,
        requiredPermissions: List<String> = emptyList()
    ): PolicyDecision = check(
        creditEngine, permissionService, outcomeScorer,
        toolName, action, requiredPermissions, dryRun = true
    )
}
