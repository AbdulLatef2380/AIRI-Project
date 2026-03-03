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
     * المعالج المركزي المحدث: يطبق نظام الـ Closed-Loop مع طبقات أمان متعددة
     */
    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        // المسار التنفيذي: إذا احتوى الطلب على كلمات مفتاحية للأوامر
        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            
            val planPrompt = """
                You are an Android Task Planner. Respond ONLY with valid JSON.
                Context: $screenContext
                User Request: ${input.text}
                JSON Format: { "goal_id": "id", "description": "desc", "steps": [...] }
            """.trimIndent()

            val rawResponse = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            // 1️⃣ Anti-Hallucination guard (أول فحص للتأكد من أن المخرج JSON)
            if (!rawResponse.trim().startsWith("{")) {
                return@coroutineScope BrainOutput("⚠ الرد غير صالح (ليس JSON)")
            }

            // تنظيف النص وتحويله لـ JSON
            val cleanedJson = cleanRawJson(rawResponse)
            
            // 2️⃣ Plan Validation (التحقق من صحة ومحتوى الخطوات)
            // ملاحظة: parsePlan هنا يعيد كائن AgentGoal (الذي يمثل الـ PlanDto في منطقنا)
            val goal = parsePlan(cleanedJson)
            
            if (goal == null || !PlanValidator.isValid(goal)) {
                return@coroutineScope BrainOutput("⚠ الخطة غير آمنة أو تالفة وتم رفضها")
            }

            // 3️⃣ Guardian Check (فحص الكلمات الحساسة قبل المرور للمنفذ)
            if (goal.description.contains("حذف") || goal.description.contains("إعادة ضبط")) {
                return@coroutineScope BrainOutput("⚠ هذا الأمر حساس ويحتاج تأكيد يدوي")
            }

            // 4️⃣ Execution with Timeout (التنفيذ مع حماية من التعليق)
            val success = withTimeoutOrNull(15000) {
                goalExecutor.executeGoal(goal)
            } ?: return@coroutineScope BrainOutput("⏳ انتهى الوقت أثناء التنفيذ")

            // 5️⃣ Feedback result (إرسال النتيجة النهائية للمستخدم)
            return@coroutineScope if (success) {
                BrainOutput("✅ تم التنفيذ بنجاح: ${goal.description}", goal.id)
            } else {
                BrainOutput("❌ فشل التنفيذ ميدانياً", goal.id)
            }
        }

        // المسار الافتراضي: محادثة عادية
        val enrichedPrompt = buildPrompt(input.text, screenContext)
        val response = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(enrichedPrompt) { result ->
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
        } catch (e: Exception) {
            null
        }
    }

    private fun cleanRawJson(raw: String): String {
        return raw.trim()
            .replace("```json", "")
            .replace("```", "")
            .let { 
                val start = it.indexOf("{")
                val end = it.lastIndexOf("}")
                if (start != -1 && end != -1) it.substring(start, end + 1) else ""
            }
    }

    private fun buildPrompt(text: String, context: String) = 
        if (context.isNotBlank()) "Context:\n$context\nUser: $text" else text
}
