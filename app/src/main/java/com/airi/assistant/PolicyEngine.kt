package com.airi.assistant

import java.time.OffsetDateTime

/**
 * AIRI Policy Engine - The Single Source of Truth for decision making.
 * Implements Fail-Closed, Versioning, and Cold Start protocols.
 */
class PolicyEngine {

    companion object {
        const val POLICY_VERSION = "1.0.3"
        const val EFFECTIVE_FROM = "2026-01-15T00:00:00Z"
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
        val constraints: Map<String, String> = emptyMap()
    )

    // Default policies (The "Constitution" of AIRI)
    // Cold Start: If this list is empty, all actions will be DENIED by evaluate()
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
     * Evaluates if an intent and action are allowed under current policies.
     * Implements Fail-Closed: Any exception or missing rule results in DENY.
     */
    fun evaluate(intent: String, action: String): EvaluationResult {
        return try {
            val rule = policies.find { it.intent == intent && it.action == action }
            
            if (rule == null) {
                // Cold Start / Missing Rule: Default to DENY
                EvaluationResult(
                    isAllowed = false,
                    reason = "No policy defined for this intent/action pair. Fail-Closed triggered.",
                    finalAction = action
                )
            } else {
                EvaluationResult(
                    isAllowed = rule.allow,
                    reason = rule.reason,
                    finalAction = action,
                    constraints = rule.constraints
                )
            }
        } catch (e: Exception) {
            // Fail-Closed: Any error during evaluation results in DENY
            EvaluationResult(
                isAllowed = false,
                reason = "Policy Engine Internal Error: ${e.message}. Fail-Closed triggered.",
                finalAction = action
            )
        }
    }

    fun updatePolicy(rule: PolicyRule) {
        policies.removeAll { it.intent == rule.intent && it.action == rule.action }
        policies.add(rule)
    }
}
