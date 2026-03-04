package com.airi.assistant.brain

object PlanValidator {

    fun validate(goal: AgentGoal) {

        if (goal.description.isBlank()) {
            throw ValidationException("Goal description is empty")
        }

        if (goal.steps.isEmpty()) {
            throw ValidationException("Goal has no steps")
        }
    }
}
