package com.airi.assistant.brain

import com.airi.assistant.LlamaManager
import com.airi.core.chain.AgentGoal
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

class PlanGenerator(
    private val llamaManager: LlamaManager
) {

    private var strategyLevel = 0
    private var retryContext = ""

    /**
     * الوظيفة: تحويل طلب المستخدم (BrainInput) مباشرة إلى هدف تنفيذي (AgentGoal)
     * الحل لخطأ Type Mismatch: تم تغيير الإرجاع ليكون AgentGoal بدلاً من DTO
     */
    suspend fun createPlan(input: BrainInput): AgentGoal {

        val prompt = buildPrompt(input)

        // 1. استدعاء موديل اللاما محلياً
        val raw = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(prompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

        // 2. تنظيف الرد واستخراج الـ JSON
        val cleaned = cleanRawJson(raw)

        // 3. تحليل الـ JSON إلى DTO (داخلياً فقط) ثم تحويله فوراً إلى AgentGoal
        val dto = PlanParser.parse(cleaned) 
            ?: throw ValidationException("تعذر تحليل خطة الذكاء الاصطناعي: تنسيق JSON غير صالح")

        // 4. استخدام الـ Builder لتحويل الـ DTO الوسيط إلى الكائن النهائي AgentGoal
        // هذا يضمن أن Controller يستلم النوع الصحيح
        return GoalBuilder.build(dto)
    }

    fun adjustStrategy() {
        strategyLevel++
        retryContext = "تنبيه: فشلت المحاولة السابقة. كن أكثر دقة في الخطوات."
    }

    fun reduceComplexity() {
        strategyLevel = (strategyLevel + 1).coerceAtMost(3)
        retryContext = "تبسيط: قدم أقل عدد ممكن من الخطوات الواضحة."
    }

    private fun buildPrompt(input: BrainInput): String {
        return """
            أنت مساعد أندرويد ذكي. أجب بصيغة JSON فقط.
            مستوى الاستراتيجية: $strategyLevel
            $retryContext
            الأمر: ${input.text}
            
            مثال للرد:
            {
              "goal_id": "${UUID.randomUUID()}",
              "description": "فتح الإعدادات"،
              "steps": [
                {"action": "click", "text": "Settings"}
              ]
            }
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
