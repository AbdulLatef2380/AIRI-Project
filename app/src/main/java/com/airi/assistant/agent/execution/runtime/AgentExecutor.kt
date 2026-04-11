package com.airi.assistant.agent.execution.runtime

import com.airi.assistant.agent.execution.command.CommandRouter
import com.airi.assistant.agent.planning.ActionPlan
import com.airi.assistant.agent.planning.PlanStep
import kotlinx.coroutines.*

object AgentExecutor {

    private var currentContext: ExecutionContext? = null
    private val executorScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * نقطة البداية لتنفيذ الخطة الذكية
     */
    fun execute(plan: ActionPlan) {
        val context = ExecutionContext(plan = plan)
        currentContext = context

        context.state = ExecutionState.PLANNING

        // التحقق من بوابة التأكيد (Confirmation Gate) قبل البدء
        if (plan.requiresConfirmation) {
            context.state = ExecutionState.WAITING_CONFIRMATION
            return
        }

        executorScope.launch {
            runExecution(context)
        }
    }

    /**
     * المحرك الداخلي الذي يدير تتابع الخطوات
     */
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

    /**
     * التنفيذ الفعلي للخطوة باستخدام CommandRouter
     */
    private suspend fun executeStep(step: PlanStep): StepResult {
        delay(150)

        val result = CommandRouter.execute(step)

        return StepResult(
            stepName = step.id, // ✔️ استخدام id بدل الكائن
            success = result.success,
            message = result.message
        )
    }

    /**
     * معالجة الفشل وتفعيل بروتوكول العودة (Rollback)
     */
    private fun handleFailure(context: ExecutionContext) {
        context.state = ExecutionState.ROLLING_BACK

        rollback(context)

        context.state = ExecutionState.FAILED
    }

    /**
     * تنفيذ rollback
     */
    private fun rollback(context: ExecutionContext) {
        for (result in context.stepHistory.reversed()) {
            if (result.success) {
                println("AIRI_AGENT: Rolling back -> ${result.stepName}")
            }
        }
    }

    /**
     * حالة التنفيذ الحالية
     */
    fun getCurrentStatus(): ExecutionState? = currentContext?.state
}
