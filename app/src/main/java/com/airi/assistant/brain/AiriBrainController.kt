package com.airi.assistant.brain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

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
                // 1️⃣ مرحلة التخطيط
                val plan = planner.createPlan(input)

                // 2️⃣ مرحلة التحقق
                validator.validate(plan)

                // 3️⃣ مرحلة التنفيذ بمهلة زمنية
                val result = withTimeoutOrNull(15000) {
                    executor.executeGoal(plan)
                } ?: throw TimeoutException("Execution timeout")

                // العودة بنتيجة التنفيذ بنجاح
                return@coroutineScope BrainOutput(
                    responseText = if (result) "✅ تم التنفيذ: ${plan.description}" else "❌ فشل التنفيذ الميداني",
                    executedGoalId = plan.id
                )

            } catch (e: Throwable) {
                // 4️⃣ تشخيص الخطأ وتحديد استراتيجية التعافي
                val strategy = recoveryManager.diagnose(e)

                // إذا استنفدنا المحاولات أو كانت الاستراتيجية هي الإيقاف
                if (!recoveryManager.shouldRetry(attempt) || strategy == RecoveryStrategy.ABORT) {
                    return@coroutineScope BrainOutput(
                        responseText = "❌ تعذر إكمال المهمة: ${e.message}",
                        executedGoalId = null
                    )
                }

                attempt++

                // 5️⃣ تعديل الخطة بناءً على الاستراتيجية المختارة
                when (strategy) {
                    RecoveryStrategy.REPLAN -> planner.adjustStrategy()
                    RecoveryStrategy.REDUCE_SCOPE -> planner.reduceComplexity()
                    else -> {
                        return@coroutineScope BrainOutput(
                            responseText = "❌ توقف التنفيذ: ${e.message}",
                            executedGoalId = null
                        )
                    }
                }
                // الحلقة ستعيد التشغيل (Retry) تلقائياً هنا بعد تعديل الاستراتيجية
            }
        }
    }
}
