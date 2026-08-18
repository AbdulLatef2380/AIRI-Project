package com.airi.assistant.world

import java.util.UUID

/**
 * ContextSnapshot — Point-in-time snapshot of system context used by the
 * accessibility and orchestration layers.
 *
 * The previous `emotionalState: EmotionEngine.State` field has been removed.
 * EmotionEngine was a dead decision-engine with 0 callers in the chat path
 * (see  deletion). Replaced with a plain String for backward compat
 * so existing callers that passed `EmotionEngine.State.name` still compile
 * after substituting the string directly.
 */
data class ContextSnapshot(
    val id:             String = UUID.randomUUID().toString(),
    val worldState:     WorldState,
    val emotionalState: String = "NEUTRAL",   // was EmotionEngine.State — removed in
    val userIntent:     String?,
    val activeTask:     String?,
    val riskAssessment: RiskEstimator.RiskAssessment,
    val timestamp:      Long = System.currentTimeMillis()
) {
    fun toSummaryString(): String = """
        [Context Snapshot ID: $id @ $timestamp]
        - World: Battery ${worldState.batteryLevel}% (${if (worldState.isCharging) "Charging" else "Discharging"}),
                 Network: ${worldState.networkType}, Memory: ${worldState.availableMemoryMB}MB
        - Emotion: $emotionalState
        - Risk: ${riskAssessment.level} (${riskAssessment.reason})
        - Intent: ${userIntent ?: "None"}
        - Task: ${activeTask ?: "Idle"}
    """.trimIndent()
}
