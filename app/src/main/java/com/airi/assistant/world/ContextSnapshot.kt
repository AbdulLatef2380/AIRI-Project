package com.airi.assistant.world

import com.airi.assistant.emotion.EmotionalState

/**
 * ContextSnapshot - A comprehensive snapshot of the system context at a specific moment.
 * This combines the physical world state with the internal emotional state and user context.
 */
data class ContextSnapshot(
    val worldState: WorldState,
    val emotionalState: EmotionalState,
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
            - Emotion: Valence ${emotionalState.valence}, Arousal ${emotionalState.arousal}, 
                       Exhaustion ${emotionalState.exhaustion}
            - Risk: ${riskAssessment.level} (${riskAssessment.reason})
            - Intent: ${userIntent ?: "None"}
            - Task: ${activeTask ?: "Idle"}
        """.trimIndent()
    }
}
