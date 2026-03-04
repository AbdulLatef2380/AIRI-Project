package com.airi.assistant.brain

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull
// ملاحظة: يفضل استخدام java.util.concurrent.TimeoutException أو تعريف خاص بك
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
                // 1️⃣ محاولة التخطيط والتنفيذ
                val goal = planner.createPlan(input)
                validator.validate(goal)

                val result = withTimeoutOrNull(15000) {
                    executor.executeGoal(goal)
                } ?: throw TimeoutException("انتهى وقت التنفيذ")

                // مخرج النجاح: ينهي الدالة ويرجع النتيجة
                return@coroutineScope BrainOutput(
                    message = if (result) "✅ تم التنفيذ: ${goal.description}" else "❌ فشل التنفيذ الميداني",
                    goalId = goal.id
                )

            } catch (e: Throwable) {
                val strategy = recoveryManager.diagnose(e)

                // 2️⃣ فحص الاستسلام (Abort)
                if (!recoveryManager.shouldRetry(attempt) || strategy == RecoveryStrategy.ABORT) {
                    return@coroutineScope BrainOutput(
                        message = "❌ تعذر إكمال المهمة: ${e.message}",
                        goalId = null
                    )
                }

                attempt++

                // 3️⃣ توجيه التدفق لإعادة المحاولة أو الإنهاء الصريح
                when (strategy) {
                    RecoveryStrategy.REPLAN -> {
                        planner.adjustStrategy()
                        continue // 🔄 يخبر المترجم بالعودة لبداية while (يحل مشكلة Unit)
                    }
                    RecoveryStrategy.REDUCE_SCOPE -> {
                        planner.reduceComplexity()
                        continue // 🔄 يخبر المترجم بالعودة لبداية while (يحل مشكلة Unit)
                    }
                    else -> {
                        // مسار الأمان النهائي
                        return@coroutineScope BrainOutput(
                            message = "❌ توقف التنفيذ لسبب غير معروف: ${e.message}",
                            goalId = null
                        )
                    }
                }
            }
        }
    }
}
