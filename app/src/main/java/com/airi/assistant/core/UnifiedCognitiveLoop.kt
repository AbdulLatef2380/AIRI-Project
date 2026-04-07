package com.airi.assistant.core

import com.airi.assistant.agent.planning.PlanGenerator
import com.airi.assistant.agent.execution.command.CommandRouter
import com.airi.assistant.agent.planning.PlanStep

/**
 * AIRI Unified Cognitive Loop - Production Grade
 * Strict dependency isolation: No Android, No Context, No AI/Memory/UI layers.
 */
class UnifiedCognitiveLoop(
    private val planGenerator: PlanGenerator = PlanGenerator()
) {

    suspend fun process(
        input: BrainInput,
        llmJson: String
    ): CognitiveResult {

        val plan = planGenerator.createActionPlanFromLLM(
            llmResponse = llmJson,
            fallbackDescription = input.text
        )

        val results = mutableListOf<StepResult>()

        plan?.steps?.forEach { step ->
            val commandResult = CommandRouter.execute(step)

            val outcome = ExecutionOutcome(
                success = commandResult.success,
                message = commandResult.message
            )

            results.add(
                StepResult(
                    step = step,
                    result = outcome
                )
            )
        }

        val allSuccess = results.all { it.result.success }

        return if (allSuccess) {
            CognitiveResult.Success(results)
        } else {
            CognitiveResult.PartialSuccess(results)
        }
    }
}

/**
 * Domain Models for Cognitive Layer
 */
data class BrainInput(
    val text: String,
    val metadata: Map<String, String> = emptyMap()
)

sealed class CognitiveResult {
    data class Success(val results: List<StepResult>) : CognitiveResult()
    data class PartialSuccess(val results: List<StepResult>) : CognitiveResult()
    data class Failed(val reason: String, val results: List<StepResult>) : CognitiveResult()
    data class Error(val message: String) : CognitiveResult()
    object AwaitingConfirmation : CognitiveResult()
}

data class StepResult(
    val step: PlanStep,
    val result: ExecutionOutcome
)

data class ExecutionOutcome(
    val success: Boolean,
    val message: String? = null
)
