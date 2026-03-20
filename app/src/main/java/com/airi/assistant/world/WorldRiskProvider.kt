package com.airi.assistant.world

import com.airi.assistant.agent.decision.RiskProvider
import com.airi.assistant.agent.decision.RiskResult

/**
 * SAFE implementation (no external dependencies)
 */
class WorldRiskProvider : RiskProvider {

    override fun estimate(action: String): RiskResult {

        // 🔥 Rule-based fallback بدل AI (مؤقت)
        val riskScore = when (action) {
            "shutdown" -> 0.9f
            "delete_data" -> 0.8f
            "start_service" -> 0.3f
            else -> 0.2f
        }

        return RiskResult(
            riskScore = riskScore,
            isCritical = riskScore > 0.85f
        )
    }
}
