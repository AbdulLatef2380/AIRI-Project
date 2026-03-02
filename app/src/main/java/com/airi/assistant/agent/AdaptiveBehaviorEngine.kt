package com.airi.assistant.agent

import com.airi.assistant.accessibility.ScreenContextHolder

object AdaptiveBehaviorEngine {

    fun buildPlan(detectedIntent: String): ActionPlan {

        val context = ScreenContextHolder.triggerExtraction()

        val confidence = ConfidenceScorer.score(detectedIntent, context)

        val steps = generateSteps(detectedIntent, context)

        val requiresConfirmation =
            BehaviorPolicy.requiresConfirmation(detectedIntent, confidence)

        return ActionPlan(
            intent = detectedIntent,
            confidence = confidence,
            steps = steps,
            requiresConfirmation = requiresConfirmation
        )
    }

    private fun generateSteps(intent: String, context: String): List<String> {

        val steps = mutableListOf<String>()

        when {
            intent.contains("open", true) ->
                steps.add("LAUNCH_APP")

            intent.contains("search", true) -> {
                steps.add("FOCUS_SEARCH")
                steps.add("TYPE_QUERY")
            }

            intent.contains("send", true) -> {
                steps.add("COMPOSE_MESSAGE")
                steps.add("VALIDATE_CONTENT")
                steps.add("CONFIRM_BEFORE_SEND")
            }

            else ->
                steps.add("UNKNOWN_ACTION")
        }

        return steps
    }
}
