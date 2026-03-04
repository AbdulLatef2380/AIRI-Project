package com.airi.assistant.brain

object PlanValidator {

    fun isValid(plan: PlanDto?): Boolean {

        if (plan == null) return false

        if (plan.steps.isEmpty()) return false

        for (step in plan.steps) {

            if (step.action.isBlank()) return false

            if (step.id.isBlank()) return false

            if (!isAllowedAction(step.action)) return false
        }

        return true
    }

    private fun isAllowedAction(action: String): Boolean {
        return when (action) {
            "click",
            "scroll",
            "wait" -> true
            else -> false
        }
    }
}
