package com.airi.assistant.brain

import com.airi.core.chain.PlanStep

object PlanValidator {

    private val allowedActions = setOf("click", "scroll")

    fun isValid(plan: PlanDto): Boolean {

        if (plan.goal_id.isBlank()) return false
        if (plan.steps.isEmpty()) return false
        if (plan.steps.size > 10) return false // منع الخطط المبالغ بها

        for (step in plan.steps) {
            if (!allowedActions.contains(step.action)) return false

            if (step.action == "click" && step.text.isNullOrBlank())
                return false
        }

        return true
    }
}
