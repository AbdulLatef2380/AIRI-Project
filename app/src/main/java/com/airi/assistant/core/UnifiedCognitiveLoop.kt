package com.airi.assistant.core

import android.util.Log
import com.airi.assistant.agent.execution.command.CommandResult
import com.airi.assistant.agent.execution.command.CommandRouter
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.BrainInput
import com.airi.assistant.agent.planning.PlanGenerator
import com.airi.assistant.agent.planning.PlanStep

class UnifiedCognitiveLoop {

    companion object {
        private const val TAG = "UnifiedCognitiveLoop"
    }

    val planGenerator = PlanGenerator()

    suspend fun process(input: BrainInput, llmResponse: String): CognitiveResult {
        Log.d(TAG, "Processing input: ${input.text}")

        return try {
            val actionPlan = planGenerator.createActionPlanFromLLM(llmResponse, input.text)
            Log.d(TAG, "Generated plan with ${actionPlan.steps.size} steps: ${actionPlan.intent}")

            if (actionPlan.requiresConfirmation) {
                return CognitiveResult.AwaitingConfirmation(actionPlan)
            }

            executeActionPlan(actionPlan)
        } catch (e: Exception) {
            Log.e(TAG, "Cognitive loop failed: ${e.message}", e)
            CognitiveResult.Error("Cognitive loop failed: ${e.message}")
        }
    }

    private suspend fun executeActionPlan(actionPlan: ActionPlan): CognitiveResult {
        val results = mutableListOf<StepResult>()

        for (step in actionPlan.steps) {
            Log.d(TAG, "Executing step: ${step.id}")
            val commandResult = CommandRouter.execute(step)

            results.add(StepResult(step, commandResult))

            if (!commandResult.success) {
                Log.w(TAG, "Step ${step.id} failed: ${commandResult.message}")
                if (isCriticalFailure(step)) {
                    return CognitiveResult.Failed(
                        plan = actionPlan,
                        results = results,
                        reason = "Critical step ${step.id} failed: ${commandResult.message}"
                    )
                }
            }
        }

        val allSucceeded = results.all { it.result.success }
        return if (allSucceeded) {
            CognitiveResult.Success(actionPlan, results)
        } else {
            CognitiveResult.PartialSuccess(actionPlan, results)
        }
    }

    private fun isCriticalFailure(step: PlanStep): Boolean {
        return step !is PlanStep.Wait && step !is PlanStep.Custom
    }
}

data class StepResult(
    val step: PlanStep,
    val result: CommandResult
)

sealed class CognitiveResult {
    data class Success(
        val plan: ActionPlan,
        val results: List<StepResult>
    ) : CognitiveResult()

    data class PartialSuccess(
        val plan: ActionPlan,
        val results: List<StepResult>
    ) : CognitiveResult()

    data class Failed(
        val plan: ActionPlan,
        val results: List<StepResult>,
        val reason: String
    ) : CognitiveResult()

    data class AwaitingConfirmation(
        val plan: ActionPlan
    ) : CognitiveResult()

    data class Error(
        val message: String
    ) : CognitiveResult()
}
