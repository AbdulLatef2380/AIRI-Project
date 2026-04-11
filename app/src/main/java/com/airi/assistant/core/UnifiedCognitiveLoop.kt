package com.airi.assistant.core

import android.util.Log
import com.airi.assistant.agent.execution.command.CommandResult
import com.airi.assistant.agent.execution.command.CommandRouter
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.BrainInput
import com.airi.assistant.agent.planning.PlanGenerator
import com.airi.assistant.agent.planning.PlanStep
import com.airi.assistant.world.WorldState
import com.airi.assistant.world.WorldStateManager

/**
 * UnifiedCognitiveLoop - Core execution engine with world context awareness
 * 
 * Unified architecture:
 * Input → Plan Generation → World Context → Execution → Result
 */
class UnifiedCognitiveLoop {

    companion object {
        private const val TAG = "UnifiedCognitiveLoop"
    }

    val planGenerator = PlanGenerator()
    
    private val worldStateManager: WorldStateManager? by lazy {
        try {
            ServiceLocator.context?.let { WorldStateManager(it) }
        } catch (e: Exception) {
            Log.w(TAG, "WorldStateManager unavailable: ${e.message}")
            null
        }
    }

    /**
     * Process user input through cognitive pipeline
     * Dual API support for backward compatibility
     */
    suspend fun process(input: String): CognitiveResult {
        val brainInput = BrainInput(text = input)
        val simplePlan = """{"goal":"$input","steps":[{"id":"1","action":"process","params":{},"depends_on":[]}]}"""
        return process(brainInput, simplePlan)
    }

    suspend fun process(input: BrainInput, llmResponse: String): CognitiveResult {
        Log.d(TAG, "━━━ Cognitive Loop Start ━━━")
        Log.d(TAG, "Input: ${input.text}")

        return try {
            // Capture world state for context
            val worldState = captureWorldState()
            logWorldState(worldState)
            
            // Generate action plan
            val actionPlan = planGenerator.createActionPlanFromLLM(llmResponse, input.text)
            Log.d(TAG, "Plan: ${actionPlan.intent} (${actionPlan.steps.size} steps)")

            // Check confirmation requirement
            if (actionPlan.requiresConfirmation) {
                Log.d(TAG, "Awaiting confirmation")
                return CognitiveResult.AwaitingConfirmation(actionPlan)
            }

            // Execute plan
            executeActionPlan(actionPlan, worldState)
            
        } catch (e: Exception) {
            Log.e(TAG, "Loop failed: ${e.message}", e)
            CognitiveResult.Error("Cognitive loop failed: ${e.message}")
        } finally {
            Log.d(TAG, "━━━ Cognitive Loop End ━━━")
        }
    }

    private fun captureWorldState(): WorldState? {
        return try {
            worldStateManager?.getCurrentState()
        } catch (e: Exception) {
            Log.w(TAG, "WorldState capture failed: ${e.message}")
            null
        }
    }
    
    private fun logWorldState(state: WorldState?) {
        if (state != null) {
            Log.d(TAG, "World → Battery: ${state.batteryLevel}%, Network: ${state.networkType}, Memory: ${state.availableMemoryMB}MB")
        } else {
            Log.d(TAG, "World → Unavailable (limited mode)")
        }
    }

    private suspend fun executeActionPlan(actionPlan: ActionPlan, worldState: WorldState?): CognitiveResult {
        val results = mutableListOf<StepResult>()

        Log.d(TAG, "Executing ${actionPlan.steps.size} steps...")
        
        for ((index, step) in actionPlan.steps.withIndex()) {
            Log.d(TAG, "Step ${index + 1}/${actionPlan.steps.size}: ${step::class.simpleName}")
            
            val commandResult = CommandRouter.execute(step)
            results.add(StepResult(step, commandResult))
            
            val icon = if (commandResult.success) "✓" else "✗"
            Log.d(TAG, "  $icon ${commandResult.message ?: "ok"}")

            if (!commandResult.success && isCriticalFailure(step)) {
                Log.e(TAG, "Critical failure, aborting")
                return CognitiveResult.Failed(actionPlan, results, "Critical step failed: ${commandResult.message}")
            }
        }

        val allSucceeded = results.all { it.result.success }
        return if (allSucceeded) {
            Log.d(TAG, "All steps succeeded")
            CognitiveResult.Success(actionPlan, results)
        } else {
            Log.w(TAG, "Partial success")
            CognitiveResult.PartialSuccess(actionPlan, results)
        }
    }

    private fun isCriticalFailure(step: PlanStep): Boolean {
        return when (step) {
            is PlanStep.Wait -> false
            is PlanStep.Custom -> false
            else -> true
        }
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
