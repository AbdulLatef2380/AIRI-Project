package com.airi.assistant.brain

import kotlinx.coroutines.coroutineScope

class AiriBrainController(
    private val planner: PlanGenerator,
    private val validator: PlanValidator,
    private val executor: GoalExecutor,
    private val recoveryManager: RecoveryManager
) {

    /**
     * معالجة الطلب وإعادة كائن BrainOutput يحتوي على الرسالة والهدف (Goal) كاملاً.
     */
    suspend fun process(input: BrainInput): BrainOutput = coroutineScope {

        // 1️⃣ مرحلة التخطيط: إنشاء كائن AgentGoal من المدخلات
        val plan = planner.createPlan(input)

        // 2️⃣ مرحلة التحقق
        validator.validate(plan)

        // 3️⃣ مرحلة التنفيذ
        val result = executor.executeGoal(plan)

        // 4️⃣ العودة بالنتيجة مع تمرير كائن plan بالكامل إلى الحقل goal
        return@coroutineScope BrainOutput(
            message = if (result) "✅ تم التنفيذ: ${plan.description}" else "❌ فشل التنفيذ الميداني",
            goal = plan // تم التغيير من goalId إلى goal
        )
    }
}
