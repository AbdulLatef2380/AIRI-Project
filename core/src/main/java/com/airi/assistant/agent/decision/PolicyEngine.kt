package com.airi.assistant.agent.decision

import java.time.OffsetDateTime

/**
 * AIRI Policy Engine - Stable Build Version (Fixed Unreachable Code)
 */
class PolicyEngine(
    private val riskProvider: RiskProvider? = null
) {

    companion object {
        const val POLICY_VERSION = "1.0.6"
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
     * Fixed evaluation (NO early returns inside try)
     */
    fun evaluate(intent: String, action: String): EvaluationResult {

        var result: EvaluationResult

        try {
            val rule = policies.find { it.intent == intent && it.action == action }

            if (rule == null) {
                result = EvaluationResult(
                    isAllowed = false,
                    reason = "No policy defined. Fail-Closed triggered.",
                    finalAction = action
                )
            } else if (!rule.allow) {
                result = EvaluationResult(
                    isAllowed = false,
                    reason = rule.reason,
                    finalAction = action,
                    constraints = rule.constraints
                )
            } else {

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
                        result = EvaluationResult(
                            isAllowed = false,
                            reason = "Risk Policy Violation",
                            finalAction = action,
                            riskLevel = riskLevel
                        )
                    } else {
                        riskReason = "RiskScore=${assessment.riskScore}"

                        result = EvaluationResult(
                            isAllowed = true,
                            reason = "Allowed ($riskReason)",
                            finalAction = action,
                            constraints = rule.constraints,
                            riskLevel = riskLevel
                        )
                    }
                } else {
                    result = EvaluationResult(
                        isAllowed = true,
                        reason = rule.reason,
                        finalAction = action,
                        constraints = rule.constraints,
                        riskLevel = riskLevel
                    )
                }
            }

        } catch (e: Exception) {
            result = EvaluationResult(
                isAllowed = false,
                reason = "Internal Error: ${e.message}. Fail-Closed triggered.",
                finalAction = action
            )
        }

        return result
    }

    fun updatePolicy(rule: PolicyRule) {
        policies.removeAll { it.intent == rule.intent && it.action == rule.action }
        policies.add(rule)
    }
}
