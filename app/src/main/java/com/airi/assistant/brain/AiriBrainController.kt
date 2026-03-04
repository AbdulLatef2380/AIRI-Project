package com.airi.assistant.brain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.TimeoutException

class AiriBrainController(
    private val planner: PlanGenerator,
    private val validator: PlanValidator,
    private val executor: GoalExecutor,
    private val recoveryManager: RecoveryManager
) {

    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        var attempt = 0

        while (true) {
            try {

                val plan = planner.createPlan(input)

                validator.validate(plan)

                val result = withTimeoutOrNull(15000) {
                    executor.executeGoal(plan)
                } ?: throw TimeoutException("Execution timeout")

                return@coroutineScope BrainOutput(
                    message = if (result)
                        "✅ تم التنفيذ: ${plan.description}"
                    else
                        "❌ فشل التنفيذ الميداني",
                    goalId = plan.id
                )

            } catch (e: Throwable) {

                val strategy = recoveryManager.diagnose(e)

                if (!recoveryManager.shouldRetry(attempt)
                    || strategy == RecoveryStrategy.ABORT
                ) {
                    return@coroutineScope BrainOutput(
                        message = "❌ تعذر إكمال المهمة: ${e.message}",
                        goalId = null
                    )
                }

                attempt++

                when (strategy) {
                    RecoveryStrategy.REPLAN -> planner.adjustStrategy()
                    RecoveryStrategy.REDUCE_SCOPE -> planner.reduceComplexity()
                    else -> {
                        return@coroutineScope BrainOutput(
                            message = "❌ توقف التنفيذ: ${e.message}",
                            goalId = null
                        )
                    }
                }
            }
        }
    }
}
