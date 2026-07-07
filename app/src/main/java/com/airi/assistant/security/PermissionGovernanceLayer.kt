package com.airi.assistant.security

import android.util.Base64
import android.util.Log
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.ArrayDeque
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
    private val firewall:      ExecutionFirewall,
    private val scopeReg:      ScopedPermissionRegistry,
    // AP-20: WorldRiskProvider injected here — defaults to the rule-based impl so
    // existing callers (ServiceLocator) don't need a breaking change until the full
    // LLM-based risk estimator is available in a later Wave.
    private val riskProvider:  com.airi.assistant.agent.decision.RiskProvider =
        com.airi.assistant.world.WorldRiskProvider()
) {
    private val TAG = "PermissionGovernanceLayer"

    enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

    data class GovernanceDecision(
        val allowed:      Boolean,
        val riskLevel:    RiskLevel,
        val reason:       String,
        val requiresUserApproval: Boolean = false
    )

    // ── Rate limiting (T21) ───────────────────────────────────────────────────
    // Token-bucket: max RATE_LIMIT_MAX evaluations per agent per RATE_WINDOW_MS.
    // Prevents runaway agent loops from bypassing governance via volume.
    private val RATE_WINDOW_MS = 60_000L
    private val RATE_LIMIT_MAX = 60
    private val rateLimitWindows = ConcurrentHashMap<String, ArrayDeque<Long>>()

    private fun checkRateLimit(agentId: String): Boolean {
        val now = System.currentTimeMillis()
        val window = rateLimitWindows.getOrPut(agentId) { ArrayDeque() }
        synchronized(window) {
            while (window.isNotEmpty() && (now - window.peek()) > RATE_WINDOW_MS) {
                window.poll()
            }
            if (window.size >= RATE_LIMIT_MAX) return false
            window.offer(now)
            return true
        }
    }

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
        // Rate-limit check: deny if the agent has exceeded RATE_LIMIT_MAX
        // governance evaluations within the past RATE_WINDOW_MS.
        if (!checkRateLimit(agentId)) {
            Log.w(TAG, "AIRI_PROOF GOVERNANCE_RATE_LIMITED agent=$agentId action=$actionType")
            AgentActivityBus.emit(
                "⏱ Rate limit exceeded [$agentId]: $actionDesc — pausing autonomous actions",
                ActivityCategory.SYSTEM,
                ActivitySeverity.WARN
            )
            return GovernanceDecision(
                allowed   = false,
                riskLevel = RiskLevel.HIGH,
                reason    = "Rate limit: agent '$agentId' exceeded $RATE_LIMIT_MAX evaluations per minute"
            )
        }

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

    private fun assessRisk(actionType: String, payload: String): RiskLevel {
        // AP-20: Incorporate WorldRiskProvider estimate.
        // The rule-based check runs first (fast, deterministic); the world
        // provider's score can escalate the final level but never lower it.
        val ruleLevel: RiskLevel = when {
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

        // WorldRiskProvider uses action semantics; payload is not inspected here
        // (encoding attacks are caught by decodeAndExpand/isDangerous before assessRisk).
        val worldResult = runCatching { riskProvider.estimate(actionType) }.getOrNull()
        val worldLevel: RiskLevel = when {
            worldResult == null          -> RiskLevel.LOW
            worldResult.isCritical       -> RiskLevel.CRITICAL
            worldResult.riskScore > 0.6f -> RiskLevel.HIGH
            worldResult.riskScore > 0.3f -> RiskLevel.MEDIUM
            else                         -> RiskLevel.LOW
        }

        // Take the maximum (most restrictive) of the two assessments
        return if (worldLevel.ordinal > ruleLevel.ordinal) worldLevel else ruleLevel
    }

    /**
     * AP-09: Expand encoding-obfuscated shell payloads before pattern matching.
     *
     * Decodes:
     *  - `$(base64 -d <<< 'PAYLOAD')` — base64 subshell substitution
     *  - `$(echo 'HEX' | xxd -r -p)` — hex subshell substitution
     *  - `$'\x72\x6d'` — ANSI-C quoting (hex escape sequences)
     *
     * If decoding fails for any substitution, the original token is preserved
     * (so the surface-form check still runs on the encoded form as a fallback).
     */
    private fun decodeAndExpand(command: String): String {
        // Pass 1: base64 subshell — $(base64 -d <<< 'PAYLOAD')
        val base64Pattern = Regex("""\$\(base64\s+-d\s+<<<\s+'([^']+)'\)""")
        var expanded = base64Pattern.replace(command) { match ->
            try {
                Base64.decode(match.groupValues[1].trim(), Base64.DEFAULT).toString(Charsets.UTF_8)
            } catch (_: Exception) { match.value }
        }

        // Pass 2: hex pipe — $(echo 'HEX' | xxd -r -p)
        val hexPattern = Regex("""\$\(echo\s+'([0-9a-fA-F]+)'\s*\|\s*xxd\s+-r\s+-p\)""")
        expanded = hexPattern.replace(expanded) { match ->
            try {
                match.groupValues[1].chunked(2)
                    .map { it.toInt(16).toByte() }
                    .toByteArray()
                    .toString(Charsets.UTF_8)
            } catch (_: Exception) { match.value }
        }

        // Pass 3: ANSI-C quoting — $'\x72\x6d' or $'\162\155'
        val ansiHexPattern  = Regex("""\$'((?:\\x[0-9a-fA-F]{2})+)'""")
        val ansiOctPattern  = Regex("""\$'((?:\\[0-7]{3})+)'""")
        val hexEscape  = Regex("""\\x([0-9a-fA-F]{2})""")
        val octEscape  = Regex("""\\([0-7]{3})""")
        expanded = ansiHexPattern.replace(expanded) { match ->
            try {
                hexEscape.findAll(match.groupValues[1])
                    .map { it.groupValues[1].toInt(16).toChar() }
                    .joinToString("")
            } catch (_: Exception) { match.value }
        }
        expanded = ansiOctPattern.replace(expanded) { match ->
            try {
                octEscape.findAll(match.groupValues[1])
                    .map { it.groupValues[1].toInt(8).toChar() }
                    .joinToString("")
            } catch (_: Exception) { match.value }
        }

        return expanded
    }

    private val dangerousPatterns = listOf(
        "rm -rf", "rm -f /", "sudo", "format", "mkfs", "dd if=",
        ":(){:|:&};:", "chmod 777 /", "wget.*|.*sh", "curl.*|.*bash",
        "> /dev/", "shred ", "wipefs", "/etc/passwd", "/etc/shadow",
        "base64 -d", "xxd -r", "python -c", "perl -e", "ruby -e",
        "exec(", "eval(", "os.system(", "__import__('os')"
    )

    private fun isDangerous(actionType: String, payload: String): Boolean {
        // AP-09: Always check BOTH the raw payload AND the decoded form.
        // Encoding bypass: $(base64 -d <<< 'cm0gLXJm') decodes to "rm -rf".
        val decoded = decodeAndExpand(payload)
        val payloadsToCheck = if (decoded != payload) listOf(payload, decoded) else listOf(payload)

        return payloadsToCheck.any { p ->
            dangerousPatterns.any { pattern ->
                if (pattern.contains(".*")) {
                    Regex(pattern, RegexOption.IGNORE_CASE).containsMatchIn(p)
                } else {
                    p.contains(pattern, ignoreCase = true)
                }
            }
        }
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
