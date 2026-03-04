package com.airi.assistant.brain

class PlanValidator {

    fun validate(plan: AgentGoal) {
        if (plan.steps.isEmpty()) {
            throw IllegalArgumentException("Plan has no steps")
        }
    }
}
