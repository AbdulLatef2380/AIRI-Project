package com.airi.assistant.brain

import java.util.UUID

class PlanGenerator {

    suspend fun createPlan(input: BrainInput): AgentGoal {

        if (input.text.isBlank()) {
            throw ValidationException("Empty input")
        }

        return AgentGoal(
            id = UUID.randomUUID().toString(),
            description = input.text,
            steps = listOf("execute")
        )
    }

    fun adjustStrategy() {}
    fun reduceComplexity() {}
}
