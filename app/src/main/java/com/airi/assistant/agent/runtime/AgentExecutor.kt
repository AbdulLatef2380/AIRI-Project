package com.airi.assistant.agent.runtime

import com.airi.assistant.agent.ActionPlan
import com.airi.assistant.agent.command.CommandRouter // 🔥 استيراد الموجه الجديد
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
            // توضيح: هنا يتم استدعاء واجهة المستخدم Overlay لطلب الإذن
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

            // تنفيذ الخطوة فعلياً عبر الطبقات العميقة
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
     * 🛠️ التعديل الجديد: الربط مع CommandRouter
     * لم نعد نستخدم "when" اليدوية، بل نعتمد على نظام الأوامر المركزي
     */
    private suspend fun executeStep(step: String): StepResult {
        // تأخير بسيط لضمان استقرار واجهة النظام بين العمليات
        delay(150)

        // إرسال الأمر للـ Router الذي بدوره يكلم الـ Accessibility Bridge
        val result = CommandRouter.execute(step)

        return StepResult(
            stepName = step,
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
     * تنفيذ الخطوات العكسية لإعادة النظام لحالته الأصلية
     */
    private fun rollback(context: ExecutionContext) {
        // العكس التاريخي: نبدأ من آخر خطوة نجحت ونعود للخلف
        for (result in context.stepHistory.reversed()) {
            if (result.success) {
                println("AIRI_AGENT: Rolling back -> ${result.stepName}")
                // هنا يمكن إضافة logic خاص بكل خطوة عكسية مستقبلاً
            }
        }
    }

    /**
     * للحصول على حالة التنفيذ الحالية (مفيد للـ UI)
     */
    fun getCurrentStatus(): ExecutionState? = currentContext?.state
}
