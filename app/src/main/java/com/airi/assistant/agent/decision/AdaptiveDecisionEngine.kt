package com.airi.assistant.agent.decision

object AdaptiveDecisionEngine {

    private const val MIN_THRESHOLD = 55

    fun shouldDisplay(app: String, intent: String): Boolean {

        val score = SuggestionScoreEngine.calculate(app, intent)

        return score >= MIN_THRESHOLD
    }
}
