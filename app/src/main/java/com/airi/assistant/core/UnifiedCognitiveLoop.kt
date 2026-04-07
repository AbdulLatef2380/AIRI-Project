package com.airi.assistant.core

import com.airi.assistant.agent.planning.PlanGenerator
import com.airi.assistant.agent.execution.command.CommandRouter

class UnifiedCognitiveLoop(
    private val planGenerator: PlanGenerator = PlanGenerator()
) {

    suspend fun process(input: String) {
        val plan = planGenerator.createPlanFromLLM(input)

        plan?.steps?.forEach { step ->
            CommandRouter.execute(step)
        }
    }
}
