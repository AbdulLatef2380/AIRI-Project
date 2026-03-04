package com.airi.assistant.brain

import com.airi.assistant.LlamaManager
// 🔥 تأكد من استيراد AgentGoal إذا كان في حزمة أخرى، أو سنقوم بتعريفه أدناه
import com.airi.core.chain.AgentGoal 
import com.airi.core.chain.PlanStep
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class PlanGenerator(
    private val llamaManager: LlamaManager
) {

    private var strategyLevel = 0
    private var retryContext = ""

    suspend fun createPlan(input: BrainInput): AgentGoal {

        val prompt = buildPrompt(input)

        val raw = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(prompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

        val cleaned = cleanRawJson(raw)

        // نستخدم المحلل (Parser) لتحويل JSON إلى DTO
        val dto = PlanParser.parse(cleaned)
            ?: throw ValidationException("فشل في تحليل JSON الخطة: الرد غير متوافق")

        // نقوم ببناء الهدف النهائي للتنفيذ
        return GoalBuilder.build(dto)
    }

    /**
     * تحسين الاستراتيجية: إضافة نصائح للموديل في المحاولة القادمة
     */
    fun adjustStrategy() {
        strategyLevel++
        retryContext = "تنبيه: المحاولة السابقة فشلت. حاول أن تكون أكثر دقة في اختيار النصوص وقم بتقليل الخطوات غير الضرورية."
    }

    /**
     * تقليل التعقيد: فرض نمط تنفيذ مبسط جداً
     */
    fun reduceComplexity() {
        strategyLevel = (strategyLevel + 1).coerceAtMost(3)
        retryContext = "تحذير: النظام يواجه صعوبة. قدم خطة من خطوة واحدة فقط إذا أمكن."
    }

    private fun buildPrompt(input: BrainInput): String {
        return """
            أنت محرك تخطيط أندرويد. أجب بصيغة JSON فقط.
            مستوى الاستراتيجية: $strategyLevel
            $retryContext
            الأمر المطلوب: ${input.text}
            
            الصيغة:
            { "goal_id": "...", "description": "...", "steps": [...] }
        """.trimIndent()
    }

    private fun cleanRawJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1)
            raw.substring(start, end + 1)
        else ""
    }
}
