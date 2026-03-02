package com.airi.assistant.agent.runtime

import com.airi.assistant.agent.ActionPlan
import kotlinx.coroutines.*

object AgentExecutor {

    private var currentContext: ExecutionContext? = null

    fun execute(plan: ActionPlan) {

        val context = ExecutionContext(plan = plan)
        currentContext = context

        context.state = ExecutionState.PLANNING

        if (plan.requiresConfirmation) {
            context.state = ExecutionState.WAITING_CONFIRMATION
            // هنا تربط Overlay لتأكيد المستخدم
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            runExecution(context)
        }
    }

    private suspend fun runExecution(context: ExecutionContext) {

        context.state = ExecutionState.EXECUTING

        for ((index, step) in context.plan.steps.withIndex()) {

            context.currentStepIndex = index

            val result = executeStep(step)

            context.stepHistory.add(result)

            if (!result.success) {
                handleFailure(context)
                return
            }
        }

        context.state = ExecutionState.COMPLETED
    }

    private suspend fun executeStep(step: String): StepResult {

        delay(200) // محاكاة تنفيذ

        return when (step) {

            "LAUNCH_APP" ->
                StepResult(step, true)

            "FOCUS_SEARCH" ->
                StepResult(step, true)

            "TYPE_QUERY" ->
                StepResult(step, true)

            "COMPOSE_MESSAGE" ->
                StepResult(step, true)

            "VALIDATE_CONTENT" ->
                StepResult(step, true)

            "CONFIRM_BEFORE_SEND" ->
                StepResult(step, false, "User confirmation required")

            else ->
                StepResult(step, false, "Unknown step")
        }
    }

    private fun handleFailure(context: ExecutionContext) {

        context.state = ExecutionState.ROLLING_BACK

        rollback(context)

        context.state = ExecutionState.FAILED
    }

    private fun rollback(context: ExecutionContext) {

        for (result in context.stepHistory.reversed()) {
            // تنفيذ عكس الخطوة
            println("Rolling back ${result.stepName}")
        }
    }
}
