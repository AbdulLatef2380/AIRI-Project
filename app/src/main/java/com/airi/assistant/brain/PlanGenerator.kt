package com.airi.assistant.brain

class PlanGenerator {

    fun createPlan(input: BrainInput): AgentGoal {

        return AgentGoal(
            id = "goal_${System.currentTimeMillis()}",
            description = input.text,
            steps = listOf(
                PlanStep.Click("default")
            )
        )
    }

    fun adjustStrategy() {}

    fun reduceComplexity() {}
}
