package com.airi.assistant.decision.AdaptiveDecisionEngine.kt

object AdaptiveDecisionEngine {

    private const val MIN_THRESHOLD = 55

    fun shouldDisplay(app: String, intent: String): Boolean {

        val score = SuggestionScoreEngine.calculate(app, intent)

        return score >= MIN_THRESHOLD
    }
}
