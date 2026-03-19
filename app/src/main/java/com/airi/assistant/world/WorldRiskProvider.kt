package com.airi.assistant.world

import com.airi.assistant.agent.decision.RiskProvider
import com.airi.assistant.agent.decision.RiskResult

class WorldRiskProvider : RiskProvider {

    private val riskEstimator = RiskEstimator()

    override fun estimate(action: String): RiskResult {
        // هنا تجيب world state من manager
        val worldState = WorldStateManager.getCurrentState()

        val assessment = riskEstimator.estimate(action, worldState)

        return RiskResult(
            riskScore = assessment.score,
            isCritical = !assessment.canProceed
        )
    }
}
