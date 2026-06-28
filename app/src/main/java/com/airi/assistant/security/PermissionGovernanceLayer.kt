package com.airi.assistant.security

import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap

/**
 * PermissionGovernanceLayer — mandatory gatekeeping layer for ALL autonomous actions.
 *
 * Every tool call, connector invocation, sandbox execution, and agent action
 * MUST pass through [evaluate] before execution. This ensures:
 *  - No autonomous action runs without a declared scope
 *  - High-risk actions require explicit user consent
 *  - Dangerous actions are intercepted and blocked
 *  - All decisions are logged to the activity feed
 *
 * Builds on top of [ExecutionFirewall] and [ScopedPermissionRegistry].
 */
class PermissionGovernanceLayer(
    private val firewall:   ExecutionFirewall,
    private val scopeReg:   ScopedPermissionRegistry
) {
    private val TAG = "PermissionGovernanceLayer"

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    data class GovernanceDecision(
        val allowed:      Boolean,
        val riskLevel:    RiskLevel,
        val reason:       String,
        val requiresUserApproval: Boolean = false
    )

    // Pending approvals waiting for user confirmation
    private val _pendingApprovals = MutableStateFlow<List<PendingApproval>>(emptyList())
    val pendingApprovals: StateFlow<List<PendingApproval>> = _pendingApprovals.asStateFlow()

    private val pending = ConcurrentHashMap<String, PendingApproval>()

    data class PendingApproval(
        val id:          String,
        val action:      String,
        val description: String,
        val riskLevel:   RiskLevel,
        val timestampMs: Long = System.currentTimeMillis()
    )

    // ── Evaluation ────────────────────────────────────────────────────────────

    /**
     * Evaluate whether an action is permitted under current governance policy.
     * This is the SINGLE entry point for all autonomous action decisions.
     *
     * @param actionType  Category of action (e.g. "shell_command", "file_write", "connector_call")
     * @param actionDesc  Human-readable description for logs/UI
     * @param agentId     The agent requesting the action
     * @param payload     The raw action payload (for risk scoring)
     */
    fun evaluate(
        actionType: String,
        actionDesc: String,
        agentId:    String = "unknown",
        payload:    String = ""
    ): GovernanceDecision {
        val risk = assessRisk(actionType, payload)

        // Block unconditionally dangerous actions
        if (isDangerous(actionType, payload)) {
            val decision = GovernanceDecision(
                allowed   = false,
                riskLevel = RiskLevel.CRITICAL,
                reason    = "Action '${actionType}' is blocked by governance policy"
            )
            AgentActivityBus.emit(
                "⛔ BLOCKED [$agentId]: $actionDesc",
                ActivityCategory.SYSTEM,
                ActivitySeverity.ERROR
            )
            Log.w(TAG, "Blocked action: type=$actionType agent=$agentId payload=${payload.take(80)}")
            return decision
        }

        // Check ExecutionFirewall (existing system) — uses allows(agentId, toolName)
        // SECURITY: fail-closed — any exception from the firewall DENIES the action.
        // An exception here means the firewall is in an unexpected state; granting
        // access in that condition would silently bypass the entire security model.
        val firewallPass = runCatching {
            firewall.allows(agentId, actionType)
        }.getOrElse { ex ->
            Log.e(TAG, "AIRI_PROOF FIREWALL_EXCEPTION agent=$agentId action=$actionType — defaulting to DENY. cause=${ex::class.simpleName}: ${ex.message}")
            AgentActivityBus.emit(
                "🚨 Firewall exception — action DENIED [$agentId]: $actionDesc",
                ActivityCategory.SYSTEM,
                ActivitySeverity.ERROR
            )
            false   // fail-closed: exceptions deny, never grant
        }

        if (!firewallPass) {
            AgentActivityBus.emit(
                "🔥 Firewall blocked [$agentId]: $actionDesc",
                ActivityCategory.SYSTEM,
                ActivitySeverity.WARN
            )
            return GovernanceDecision(false, risk, "ExecutionFirewall policy violation")
        }

        // High-risk actions are allowed but logged with WARNING
        if (risk == RiskLevel.HIGH) {
            AgentActivityBus.emit(
                "⚠ High-risk action [$agentId]: $actionDesc",
                ActivityCategory.SYSTEM,
                ActivitySeverity.WARN
            )
        }

        return GovernanceDecision(allowed = true, riskLevel = risk, reason = "Permitted")
    }

    // ── Risk scoring ──────────────────────────────────────────────────────────

    private fun assessRisk(actionType: String, payload: String): RiskLevel = when {
        actionType.startsWith("shell")        -> RiskLevel.HIGH
        actionType.startsWith("file_delete")  -> RiskLevel.HIGH
        actionType.startsWith("connector")    -> RiskLevel.MEDIUM
        actionType.startsWith("file_write")   -> RiskLevel.MEDIUM
        actionType.startsWith("network")      -> RiskLevel.MEDIUM
        payload.contains("rm -rf")            -> RiskLevel.CRITICAL
        payload.contains("sudo")              -> RiskLevel.CRITICAL
        payload.contains("format")            -> RiskLevel.CRITICAL
        else                                  -> RiskLevel.LOW
    }

    private fun isDangerous(actionType: String, payload: String): Boolean {
        val dangerousPatterns = listOf(
            "rm -rf", "sudo", "format", "mkfs", "dd if=", ":(){:|:&};:",
            "chmod 777 /", "wget.*|.*sh", "curl.*|.*bash"
        )
        return dangerousPatterns.any { payload.contains(it, ignoreCase = true) }
    }

    // ── User approval flow ────────────────────────────────────────────────────

    fun requestApproval(action: String, description: String, riskLevel: RiskLevel): String {
        val id = java.util.UUID.randomUUID().toString().take(8)
        val approval = PendingApproval(id, action, description, riskLevel)
        pending[id] = approval
        _pendingApprovals.value = pending.values.toList()
        AgentActivityBus.emit("⏳ Awaiting approval: $description", ActivityCategory.SYSTEM, ActivitySeverity.WARN)
        return id
    }

    fun approveAction(approvalId: String) {
        pending.remove(approvalId)
        _pendingApprovals.value = pending.values.toList()
        AgentActivityBus.emit("✅ Action approved: $approvalId", ActivityCategory.SYSTEM)
    }

    fun denyAction(approvalId: String) {
        pending.remove(approvalId)
        _pendingApprovals.value = pending.values.toList()
        AgentActivityBus.emit("❌ Action denied: $approvalId", ActivityCategory.SYSTEM, ActivitySeverity.WARN)
    }
}
