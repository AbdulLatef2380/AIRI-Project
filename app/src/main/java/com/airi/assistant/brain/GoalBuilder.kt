package com.airi.assistant.brain

import com.airi.core.chain.AgentGoal
import com.airi.core.chain.PlanStep

object GoalBuilder {

    fun build(dto: PlanDto): AgentGoal {

        // تحويل الخطوات من Dto إلى كائنات تنفيذية
        val steps = dto.steps.map { step ->
            when (step.action) {
                // استخدمنا step.text لأن PlanDto يحتوي على text وليس id بناءً على الكود السابق
                "click" -> PlanStep.Click(step.text) 
                "scroll" -> PlanStep.ScrollForward()
                "wait" -> PlanStep.WaitFor(step.text)
                else -> PlanStep.Click(step.text)
            }
        }

        // بناء كائن الهدف النهائي
        return AgentGoal(
            id = "goal_${System.currentTimeMillis()}", // تم تصحيح الاسم من goalId إلى id
            description = dto.description, // نأخذ الوصف من الـ DTO ليكون أدق
            steps = steps
        )
    }
}
