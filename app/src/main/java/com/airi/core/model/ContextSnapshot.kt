package com.airi.core.model

data class ContextSnapshot(
    val currentIntent: IntentData,
    val recentMessages: List<String>,
    val emotionalState: EmotionalState,
    val memorySummary: String?
)
