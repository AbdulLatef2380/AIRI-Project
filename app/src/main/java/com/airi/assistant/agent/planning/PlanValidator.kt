package com.airi.assistant.agent.planning
import com.airi.core.planning.AgentGoal

class PlanValidator {

    fun validate(plan: AgentGoal) {
        if (plan.steps.isEmpty()) {
            throw IllegalArgumentException("Plan has no steps")
        }
    }
}
