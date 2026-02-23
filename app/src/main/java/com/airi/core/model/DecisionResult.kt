package com.airi.core.model

data class DecisionResult(
    val intentId: String,
    val action: DecisionAction,
    val confidence: Float,
    val reasoning: String? = null
)

enum class DecisionAction {
    RESPOND,
    EXECUTE,
    DEFER,
    BLOCK,
    ESCALATE
}
