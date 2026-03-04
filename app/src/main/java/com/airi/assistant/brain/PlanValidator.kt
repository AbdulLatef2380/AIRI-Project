package com.airi.assistant.brain

object PlanValidator {

    /**
     * يقوم بفحص الخطة ويرمي [ValidationException] في حال وجود خلل.
     * هذا يسمح لـ AiriBrainController بالتقاط الخطأ وتشخيص استراتيجية التعافي.
     */
    fun validate(plan: PlanDto?) {

        if (plan == null) {
            throw ValidationException("الخطة فارغة تماماً (Null)")
        }

        if (plan.steps.isEmpty()) {
            throw ValidationException("الخطة لا تحتوي على أي خطوات تنفيذية")
        }

        for (step in plan.steps) {
            // التحقق من الإجراء
            if (step.action.isBlank()) {
                throw ValidationException("وجد خطوة بإجراء فارغ")
            }

            // ملاحظة: تم استخدام step.text بدلاً من step.id لتتوافق مع تعريف StepDto السابق
            if (step.action == "click" && step.text.isBlank()) {
                throw ValidationException("إجراء النقر يتطلب نصاً لتحديده")
            }

            if (!isAllowedAction(step.action)) {
                throw ValidationException("الإجراء '${step.action}' غير مدعوم في النظام")
            }
        }
    }

    /**
     * نسخة تعيد Boolean للتوافق مع الأجزاء الأخرى من التطبيق إذا لزم الأمر
     */
    fun isValid(plan: PlanDto?): Boolean {
        return try {
            validate(plan)
            true
        } catch (e: ValidationException) {
            false
        }
    }

    private fun isAllowedAction(action: String): Boolean {
        return when (action.lowercase()) {
            "click",
            "scroll",
            "wait" -> true
            else -> false
        }
    }
}
