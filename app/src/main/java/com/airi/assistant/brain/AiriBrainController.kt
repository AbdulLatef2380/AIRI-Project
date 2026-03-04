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
                // 1️⃣ مرحلة التخطيط (تحويل النص إلى خطة عمل)
                val plan = planner.createPlan(input)

                // 2️⃣ مرحلة التحقق (التأكد من سلامة الخطوات)
                validator.validate(plan)

                // 3️⃣ مرحلة التنفيذ بمهلة زمنية (15 ثانية)
                val result = withTimeoutOrNull(15000) {
                    executor.executeGoal(plan)
                } ?: throw TimeoutException("Execution timeout")

                // العودة بالنتيجة باستخدام العقد الموحد (Success Case)
                return@coroutineScope BrainOutput(
                    message = if (result) "✅ تم التنفيذ: ${plan.description}" else "❌ فشل التنفيذ الميداني",
                    goalId = plan.id
                )

            } catch (e: Throwable) {
                // 4️⃣ تشخيص الخطأ وتحديد استراتيجية التعافي
                val strategy = recoveryManager.diagnose(e)

                // إذا استنفدنا المحاولات أو كانت الاستراتيجية هي الإيقاف النهائي
                if (!recoveryManager.shouldRetry(attempt) || strategy == RecoveryStrategy.ABORT) {
                    return@coroutineScope BrainOutput(
                        message = "❌ تعذر إكمال المهمة: ${e.message}",
                        goalId = null
                    )
                }

                attempt++

                // 5️⃣ تعديل الاستراتيجية قبل المحاولة القادمة
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
                // ستتم إعادة المحاولة تلقائياً بفضل حلقة while(true)
            }
        }
    }
}
