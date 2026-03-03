package com.airi.assistant.brain

import android.util.Log
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.LlamaManager
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.PlanStep
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

class AiriBrainController(
    private val llamaManager: LlamaManager,
    private val goalExecutor: GoalExecutor 
) {

    /**
     * المعالج المركزي: المايسترو الذي يدير طبقات الحماية والتنفيذ
     */
    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        // المسار التنفيذي: (افتح، اضغط، نفذ...)
        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            
            // 🔥 الـ Prompt الصارم جداً لمنع الهلوسة والالتزام بالصيغة
            val planPrompt = """
                أنت محرك تخطيط لنظام أندرويد.
                أعد الرد بصيغة JSON فقط.
                ممنوع أي نص إضافي.
                ممنوع استخدام markdown (```json).
                ممنوع تقديم أي شرح أو ملاحظات.

                الصيغة المطلوبة:
                {
                  "goal_id": "تعريف_فريد",
                  "description": "وصف دقيق باللغة العربية",
                  "steps": [
                    { "action": "click", "text": "نص_الزر" },
                    { "action": "scroll" },
                    { "action": "wait", "text": "نص_العنصر" }
                  ]
                }

                السياق الحالي للشاشة:
                $screenContext

                الأمر المطلوب:
                ${input.text}
            """.trimIndent()

            val rawResponse = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            // 1️⃣ JSON Guard: منع الهلوسة النصية
            val trimmedResponse = rawResponse.trim()
            if (!trimmedResponse.startsWith("{")) {
                Log.e("AIRI_GUARD", "Hallucination detected: Not a JSON start")
                return@coroutineScope BrainOutput("⚠ الرد غير صالح (ليس JSON). يرجى المحاولة بصياغة أوضح.")
            }

            // تنظيف النص (في حال أضاف LLM وسوم markdown رغم المنع)
            val cleanedJson = cleanRawJson(trimmedResponse)
            
            // 2️⃣ Plan Validation: فحص هيكلية الخطوات وسلامتها
            val goal = parsePlan(cleanedJson)
            if (goal == null || !PlanValidator.isValid(goal)) {
                return@coroutineScope BrainOutput("⚠ الخطة غير آمنة أو غير مكتملة وتم رفضها.")
            }

            // 3️⃣ Guardian Check: صمام الأمان الأخير للأوامر الحساسة
            if (goal.description.contains("حذف") || goal.description.contains("إعادة ضبط") || 
                goal.description.contains("مسح") || goal.description.contains("فرمتة")) {
                return@coroutineScope BrainOutput("⚠ هذا الأمر حساس جداً ويتطلب تأكيداً يدوياً.")
            }

            // 4️⃣ Timeout Execution: التنفيذ مع مراقبة الوقت (Closed-Loop)
            val success = withTimeoutOrNull(15000) {
                goalExecutor.executeGoal(goal)
            } ?: return@coroutineScope BrainOutput("⏳ انتهى الوقت المحدد أثناء محاولة التنفيذ.")

            // 5️⃣ Feedback result: إرسال النتيجة النهائية
            return@coroutineScope if (success) {
                BrainOutput("✅ تم التنفيذ بنجاح: ${goal.description}", goal.id)
            } else {
                BrainOutput("❌ فشل التنفيذ الميداني. ربما تغيرت واجهة التطبيق.", goal.id)
            }
        }

        // المسار الافتراضي: محادثة عادية
        val response = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(buildPrompt(input.text, screenContext)) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        return@coroutineScope BrainOutput(responseText = response)
    }

    private fun parsePlan(json: String): AgentGoal? {
        return try {
            val obj = JSONObject(json)
            val steps = mutableListOf<PlanStep>()
            val stepsArray = obj.getJSONArray("steps")
            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                when (stepObj.getString("action")) {
                    "click" -> steps.add(PlanStep.Click(stepObj.getString("text")))
                    "scroll" -> steps.add(PlanStep.ScrollForward())
                    "wait" -> steps.add(PlanStep.WaitFor(stepObj.optString("text", "")))
                }
            }
            AgentGoal(obj.getString("goal_id"), obj.getString("description"), steps)
        } catch (e: Exception) { null }
    }

    private fun cleanRawJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1) raw.substring(start, end + 1) else ""
    }

    private fun buildPrompt(text: String, context: String) = 
        if (context.isNotBlank()) "Context:\n$context\nUser: $text" else text
}
