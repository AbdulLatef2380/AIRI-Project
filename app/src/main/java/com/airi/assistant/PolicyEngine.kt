package com.airi.assistant

import com.airi.assistant.world.RiskEstimator
import com.airi.assistant.world.WorldState
import java.time.OffsetDateTime

/**
 * AIRI Policy Engine - The Single Source of Truth for decision making.
 * Implements Fail-Closed, Versioning, and Cold Start protocols.
 * Updated to include World Model Risk Estimation.
 */
class PolicyEngine {

    companion object {
        const val POLICY_VERSION = "1.0.4"
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

    private val riskEstimator = RiskEstimator()

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
     * Evaluates if an intent and action are allowed under current policies and world state.
     * Implements Fail-Closed: Any exception or missing rule results in DENY.
     */
    fun evaluate(intent: String, action: String, worldState: WorldState? = null): EvaluationResult {
        return try {
            val rule = policies.find { it.intent == intent && it.action == action }
            
            // 1. Basic Policy Check
            if (rule == null) {
                return EvaluationResult(
                    isAllowed = false,
                    reason = "No policy defined for this intent/action pair. Fail-Closed triggered.",
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

            // 2. World Model Risk Check (If state is provided)
            var riskReason = ""
            var riskLevel = "LOW"
            if (worldState != null) {
                val assessment = riskEstimator.estimate(action, worldState)
                riskLevel = assessment.level.name
                if (!assessment.canProceed) {
                    return EvaluationResult(
                        isAllowed = false,
                        reason = "Risk Policy Violation: ${assessment.reason}",
                        finalAction = action,
                        riskLevel = riskLevel
                    )
                }
                riskReason = assessment.reason
            }

            // 3. Final Approval
            EvaluationResult(
                isAllowed = true,
                reason = if (riskReason.isNotEmpty()) "Allowed ($riskReason)" else rule.reason,
                finalAction = action,
                constraints = rule.constraints,
                riskLevel = riskLevel
            )

        } catch (e: Exception) {
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
