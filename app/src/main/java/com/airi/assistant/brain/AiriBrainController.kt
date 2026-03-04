package com.airi.assistant.brain

import kotlinx.coroutines.coroutineScope

class AiriBrainController(
    private val planner: PlanGenerator,
    private val validator: PlanValidator,
    private val executor: GoalExecutor,
    private val recoveryManager: RecoveryManager
) {

    /**
     * معالجة الطلب بشكل خطي ومضمون العودة بـ BrainOutput
     * هذا الشكل يحل مشكلة الـ Type Mismatch نهائياً
     */
    suspend fun process(input: BrainInput): BrainOutput = coroutineScope {

        // 1️⃣ مرحلة التخطيط: تحويل المدخلات إلى خطة عمل
        val plan = planner.createPlan(input)

        // 2️⃣ مرحلة التحقق: التأكد من سلامة الخطوات
        validator.validate(plan)

        // 3️⃣ مرحلة التنفيذ: تشغيل المهمة ميدانياً
        val result = executor.executeGoal(plan)

        // 4️⃣ العودة بالنتيجة: مسار واحد واضح ومضمون للمترجم
        return@coroutineScope BrainOutput(
            message = if (result) "✅ تم التنفيذ: ${plan.description}" else "❌ فشل التنفيذ الميداني",
            goalId = plan.id
        )
    }
}
