package com.airi.assistant.agent.decision

import java.time.OffsetDateTime

/**
 * AIRI Policy Engine - The Single Source of Truth for decision making.
 * Clean Architecture version (NO direct dependency on world module).
 */
class PolicyEngine(
    private val riskProvider: RiskProvider? = null
) {

    companion object {
        const val POLICY_VERSION = "1.0.5"
        const val EFFECTIVE_FROM = "2026-02-19T00:00:00Z"
    }

    data class PolicyRule(
        val intent: String,
        val action: String,
        val allow: Boolean,
        val reason: String,
        val constraints: Map<String, String> = emptyMap()
    )

    data class EvaluationResult(
        val isAllowed: Boolean,
        val reason: String,
        val finalAction: String,
        val policyVersion: String = POLICY_VERSION,
        val timestamp: String = OffsetDateTime.now().toString(),
        val constraints: Map<String, String> = emptyMap(),
        val riskLevel: String = "UNKNOWN"
    )

    // Default policies (The "Constitution" of AIRI)
    private val policies = mutableListOf(
        PolicyRule(
            intent = "system",
            action = "shutdown",
            allow = false,
            reason = "Critical operation requires human approval"
        ),
        PolicyRule(
            intent = "automation_request",
            action = "create_task",
            allow = true,
            reason = "Standard automation allowed",
            constraints = mapOf("rate_limit" to "5/min")
        ),
        PolicyRule(
            intent = "cybersecurity",
            action = "scan_logs",
            allow = true,
            reason = "Analysis within safe scope",
            constraints = mapOf("scope" to "read-only")
        ),
        PolicyRule(
            intent = "privacy",
            action = "access_contacts",
            allow = false,
            reason = "Privacy boundary: Direct contact access is restricted"
        )
    )

    /**
     * Evaluates if an intent and action are allowed.
     * Implements Fail-Closed strategy.
     */
    fun evaluate(intent: String, action: String): EvaluationResult {
        return try {
            val rule = policies.find { it.intent == intent && it.action == action }

            // 1. Basic Policy Check
            if (rule == null) {
                return EvaluationResult(
                    isAllowed = false,
                    reason = "No policy defined. Fail-Closed triggered.",
                    finalAction = action
                )
            }

            if (!rule.allow) {
                return EvaluationResult(
                    isAllowed = false,
                    reason = rule.reason,
                    finalAction = action,
                    constraints = rule.constraints
                )
            }

            // 2. Risk Check عبر abstraction
            var riskLevel = "LOW"
            var riskReason = ""

            if (riskProvider != null) {
                val assessment = riskProvider.estimate(action)

                riskLevel = when {
                    assessment.isCritical -> "HIGH"
                    assessment.riskScore > 0.5f -> "MEDIUM"
                    else -> "LOW"
                }

                if (assessment.isCritical) {
                    return EvaluationResult(
                        isAllowed = false,
                        reason = "Risk Policy Violation",
                        finalAction = action,
                        riskLevel = riskLevel
                    )
                }

                riskReason = "RiskScore=${assessment.riskScore}"
            }

            // 3. Final Approval
            return EvaluationResult(
                isAllowed = true,
                reason = if (riskReason.isNotEmpty()) {
                    "Allowed ($riskReason)"
                } else {
                    rule.reason
                },
                finalAction = action,
                constraints = rule.constraints,
                riskLevel = riskLevel
            )

        } catch (e: Exception) {
            return EvaluationResult(
                isAllowed = false,
                reason = "Internal Error: ${e.message}. Fail-Closed triggered.",
                finalAction = action
            )
        }
    }

    fun updatePolicy(rule: PolicyRule) {
        policies.removeAll { it.intent == rule.intent && it.action == rule.action }
        policies.add(rule)
    }
}
