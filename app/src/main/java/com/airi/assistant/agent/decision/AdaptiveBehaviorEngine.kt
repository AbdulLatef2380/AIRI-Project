package com.airi.assistant.agent.decision

import com.airi.assistant.accessibility.service.ScreenContextHolder
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.PlanStep

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

    /**
     * Generate PlanStep objects based on the detected intent.
     * Returns List<PlanStep> to match ActionPlan.steps type.
     */
    private fun generateSteps(intent: String, context: String): List<PlanStep> {

        val steps = mutableListOf<PlanStep>()

        when {
            intent.contains("open", true) ->
                steps.add(
                    PlanStep.OpenApp(
                        id = "step_open_1",
                        appName = extractTargetApp(intent)
                    )
                )

            intent.contains("search", true) -> {
                steps.add(
                    PlanStep.Click(
                        id = "step_search_1",
                        targetText = "search"
                    )
                )
                steps.add(
                    PlanStep.Type(
                        id = "step_type_1",
                        text = extractSearchQuery(intent),
                        dependsOn = listOf("step_search_1")
                    )
                )
            }

            intent.contains("send", true) -> {
                steps.add(
                    PlanStep.Custom(
                        id = "step_compose_1",
                        action = "compose_message"
                    )
                )
                steps.add(
                    PlanStep.Custom(
                        id = "step_validate_1",
                        action = "validate_content",
                        dependsOn = listOf("step_compose_1")
                    )
                )
                steps.add(
                    PlanStep.Custom(
                        id = "step_confirm_1",
                        action = "confirm_before_send",
                        dependsOn = listOf("step_validate_1")
                    )
                )
            }

            else ->
                steps.add(
                    PlanStep.Custom(
                        id = "step_unknown_1",
                        action = "unknown_action"
                    )
                )
        }

        return steps
    }

    private fun extractTargetApp(intent: String): String {
        // Simple extraction — e.g. "open Chrome" -> "Chrome"
        val words = intent.split(" ")
        val openIdx = words.indexOfFirst { it.equals("open", ignoreCase = true) }
        return if (openIdx >= 0 && openIdx + 1 < words.size) words[openIdx + 1] else intent
    }

    private fun extractSearchQuery(intent: String): String {
        return intent.replace(Regex("search\\s*", RegexOption.IGNORE_CASE), "").trim()
    }
}
