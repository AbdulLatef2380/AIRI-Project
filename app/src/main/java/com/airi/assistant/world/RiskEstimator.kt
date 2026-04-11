package com.airi.assistant.world

import android.util.Log

/**
 * RiskEstimator - Estimates the risk of an action based on the current world state.
 */
class RiskEstimator {

    enum class RiskLevel {
        LOW, MEDIUM, HIGH, CRITICAL
    }

    data class RiskAssessment(
        val level: RiskLevel,
        val reason: String,
        val canProceed: Boolean
    )

    fun estimate(action: String, state: WorldState): RiskAssessment {
        // 1. Check Battery Risk
        if (state.batteryLevel < 15 && !state.isCharging) {
            if (isHeavyAction(action)) {
                return RiskAssessment(RiskLevel.HIGH, "Battery too low for heavy operations", false)
            }
        }

        // 2. Check Network Risk
        if (!state.isNetworkConnected && isNetworkAction(action)) {
            return RiskAssessment(RiskLevel.CRITICAL, "No internet connection for network action", false)
        }

        // 3. Check Memory Risk
        if (state.availableMemoryMB < 200) {
            return RiskAssessment(RiskLevel.MEDIUM, "Low memory, performance may be affected", true)
        }

        // 4. Default Low Risk
        return RiskAssessment(RiskLevel.LOW, "Safe to proceed", true)
    }

    private fun isHeavyAction(action: String): Boolean {
        val heavyKeywords = listOf("inference", "download", "update", "scan", "process")
        return heavyKeywords.any { action.lowercase().contains(it) }
    }

    private fun isNetworkAction(action: String): Boolean {
        val networkKeywords = listOf("http", "webhook", "n8n", "cloud", "sync", "upload")
        return networkKeywords.any { action.lowercase().contains(it) }
    }
}
