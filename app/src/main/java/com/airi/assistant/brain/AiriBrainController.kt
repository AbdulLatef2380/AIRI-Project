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

    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        // 1️⃣ فحص نية التنفيذ (Execution Flow)
        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            
            val planPrompt = """
                You are an Android AI Agent. Respond ONLY with valid JSON.
                Context: $screenContext
                User Request: ${input.text}
                JSON Format:
                {
                  "goal_id": "task_id",
                  "description": "Short description",
                  "steps": [{"action": "click", "text": "target"}]
                }
            """.trimIndent()

            val rawJson = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            val cleanedJson = cleanRawJson(rawJson)
            val goal = parsePlan(cleanedJson)

            // 🛡️ صمام الأمان 1: التحقق من هيكلية الخطة عبر Validator
            // نمرر الـ Goal المنشأ للتحقق من سلامة الأوامر
            if (goal == null || !PlanValidator.isValid(goal)) {
                return@coroutineScope BrainOutput(
                    responseText = "⚠ الخطة غير آمنة أو غير مدعومة وتم رفضها تلقائياً."
                )
            }

            // 🛡️ صمام الأمان 2: Guardian Check للأوامر الحساسة
            if (goal.description.contains("حذف") || goal.description.contains("إعادة ضبط") || 
                goal.description.contains("مسح") || goal.description.contains("format")) {
                return@coroutineScope BrainOutput(
                    responseText = "🔒 تنبيه أمني: الخطة تحتوي على عمليات حساسة. يرجى تأكيد التنفيذ يدوياً."
                )
            }

            // 🔄 التنفيذ مع انتظار النتيجة (Closed-Loop) وحماية الوقت (Timeout)
            return@coroutineScope try {
                // ننتظر نتيجة التنفيذ لمدة أقصاها 15 ثانية
                val isSuccess = withTimeout(15000) { 
                    goalExecutor.executeGoal(goal)
                }

                if (isSuccess) {
                    BrainOutput(
                        responseText = "✅ تم التنفيذ بنجاح: ${goal.description}",
                        executedGoalId = goal.id
                    )
                } else {
                    BrainOutput(
                        responseText = "❌ فشل التنفيذ ميدانياً. قد يكون العنصر غير متاح حالياً.",
                        executedGoalId = goal.id
                    )
                }
            } catch (e: TimeoutCancellationException) {
                BrainOutput("⏳ استغرقت العملية وقتاً طويلاً وتم إيقافها لضمان استقرار النظام.")
            }
        }

        // 2️⃣ المسار الافتراضي: محادثة عادية
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
            val goalId = obj.getString("goal_id")
            val description = obj.getString("description")
            val stepsArray = obj.getJSONArray("steps")
            val steps = mutableListOf<PlanStep>()

            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                when (stepObj.getString("action")) {
                    "click" -> steps.add(PlanStep.Click(stepObj.getString("text")))
                    "scroll" -> steps.add(PlanStep.ScrollForward())
                    "wait" -> steps.add(PlanStep.WaitFor(stepObj.optString("text", "")))
                }
            }
            AgentGoal(goalId, description, steps)
        } catch (e: Exception) {
            Log.e("AIRI_PARSER", "JSON Error: ${e.message}")
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
