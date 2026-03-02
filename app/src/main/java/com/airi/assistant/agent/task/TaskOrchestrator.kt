package com.airi.assistant.agent.task

import android.accessibilityservice.AccessibilityService
import com.airi.assistant.agent.validation.TemporalValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class TaskOrchestrator(
    private val service: AccessibilityService
) {

    suspend fun execute(chain: TaskChain): Boolean {

        while (chain.hasMore()) {

            val step = chain.next() ?: break

            val result = executeStep(step)

            if (!result) {
                return handleFailure(chain)
            }

            val valid = TemporalValidator.validateAction(service)

            if (!valid) {
                return handleFailure(chain)
            }
        }

        return true
    }

    private suspend fun executeStep(step: TaskStep): Boolean {
        return withContext(Dispatchers.Main) {

            when (step) {

                is TaskStep.FocusField -> {
                    // استخدم SemanticRanker + DecisionEngine
                    true
                }

                is TaskStep.TypeText -> {
                    // نفذ type
                    true
                }

                is TaskStep.ClickButton -> {
                    // نفذ click
                    true
                }

                TaskStep.ValidateState -> {
                    true
                }

                TaskStep.Rollback -> {
                    performGlobalBack()
                    true
                }
            }
        }
    }

    private fun handleFailure(chain: TaskChain): Boolean {
        chain.reset()
        return false
    }

    private fun performGlobalBack(): Boolean {
        return service.performGlobalAction(
            AccessibilityService.GLOBAL_ACTION_BACK
        )
    }
}
