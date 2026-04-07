package com.airi.assistant.core

import java.text.SimpleDateFormat
import java.util.*

/**
 * AIRI Policy Engine - Stable Build Version (API-safe)
 * Moved to core package for strict dependency isolation.
 */
class PolicyEngine {

    companion object {
        const val POLICY_VERSION = "1.0.6"
        const val EFFECTIVE_FROM = "2026-02-19T00:00:00Z"

        private fun currentTimestamp(): String {
            return SimpleDateFormat(
                "yyyy-MM-dd'T'HH:mm:ss",
                Locale.getDefault()
            ).format(Date())
        }
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
        val timestamp: String = currentTimestamp(),
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

    fun evaluate(intent: String, action: String): EvaluationResult {
        return try {
            val rule = policies.find { it.intent == intent && it.action == action }

            if (rule == null) {
                EvaluationResult(
                    isAllowed = false,
                    reason = "No policy defined. Fail-Closed triggered.",
                    finalAction = action
                )
            } else if (!rule.allow) {
                EvaluationResult(
                    isAllowed = false,
                    reason = rule.reason,
                    finalAction = action,
                    constraints = rule.constraints
                )
            } else {
                EvaluationResult(
                    isAllowed = true,
                    reason = rule.reason,
                    finalAction = action,
                    constraints = rule.constraints,
                    riskLevel = "LOW"
                )
            }
        } catch (e: Exception) {
            EvaluationResult(
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
