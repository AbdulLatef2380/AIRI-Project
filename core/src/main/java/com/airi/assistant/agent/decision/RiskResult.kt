package com.airi.assistant.agent.decision

/**
 * Result returned from RiskProvider
 */
data class RiskResult(
    val riskScore: Float,
    val isCritical: Boolean
)
