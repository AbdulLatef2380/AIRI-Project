package com.airi.assistant.world

import com.airi.assistant.EmotionEngine

/**
 * ContextSnapshot - A comprehensive snapshot of the system context at a specific moment.
 * This combines the physical world state with the internal emotional state and user context.
 */
data class ContextSnapshot(
    val worldState: WorldState,
    val emotionalState: EmotionEngine.State,
    val userIntent: String?,
    val activeTask: String?,
    val riskAssessment: RiskEstimator.RiskAssessment,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * Formats the snapshot into a readable string for the LLM or logs.
     */
    fun toSummaryString(): String {
        return """
            [Context Snapshot @ $timestamp]
            - World: Battery ${worldState.batteryLevel}% (${if (worldState.isCharging) "Charging" else "Discharging"}), 
                     Network: ${worldState.networkType}, Memory: ${worldState.availableMemoryMB}MB
            - Emotion: ${emotionalState.name}
            - Risk: ${riskAssessment.level} (${riskAssessment.reason})
            - Intent: ${userIntent ?: "None"}
            - Task: ${activeTask ?: "Idle"}
        """.trimIndent()
    }
}
