package com.airi.assistant.agent.decision

/**
 * Interface for risk estimation providers.
 */
interface RiskProvider {
    fun estimate(action: String): RiskResult
}

/**
 * Result from a risk estimation.
 */
data class RiskResult(
    val riskScore: Float,       // 0.0 (safe) to 1.0 (critical)
    val isCritical: Boolean
)
