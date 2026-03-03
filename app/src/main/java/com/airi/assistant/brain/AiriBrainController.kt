package com.airi.assistant.brain

import android.util.Log
import com.airi.assistant.ai.IntentDetector
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.LlamaManager
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.PlanStep
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

class AiriBrainController(
    private val llamaManager: LlamaManager,
    private val goalExecutor: GoalExecutor // 🔥 إضافة واجهة التنفيذ للمشغل
) {

    /**
     * المعالج المركزي: يحلل الطلب، يولد JSON، ثم يرسل الهدف للتنفيذ الفعلي
     */
    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        
        // 1️⃣ استخراج سياق الشاشة إذا لزم الأمر
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        // 2️⃣ فحص نية التنفيذ: (افتح، اضغط، نفذ...)
        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            
            val planPrompt = """
                You are an Android AI Agent. 
                Respond ONLY with a valid JSON object. No conversation.
                
                Context: $screenContext
                User Request: ${input.text}
                
                JSON Format:
                {
                  "goal_id": "task_id",
                  "description": "Short description of the plan",
                  "steps": [
                    {"action": "click", "text": "button_text"},
                    {"action": "scroll"},
                    {"action": "wait", "text": "element_to_wait_for"}
                  ]
                }
            """.trimIndent()

            val rawJson = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            // تنظيف ومعالجة الـ JSON
            val cleanedJson = cleanRawJson(rawJson)
            val goal = parsePlan(cleanedJson)

            if (goal != null) {
                // 🔥 إرسال الهدف إلى AccessibilityService عبر الـ Executor
                goalExecutor.executeGoal(goal)

                return@coroutineScope BrainOutput(
                    responseText = "🚀 جاري تنفيذ: ${goal.description}",
                    executedGoalId = goal.id
                )
            } else {
                Log.e("AIRI_BRAIN", "Failed to construct a plan. Raw output: $rawJson")
            }
        }

        // 3️⃣ المسار الافتراضي: محادثة عادية (LLM Chat)
        val enrichedPrompt = buildPrompt(input.text, screenContext)
        val response = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(enrichedPrompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

        return@coroutineScope BrainOutput(responseText = response)
    }

    /**
     * تحويل النص المستلم إلى كائن AgentGoal إجرائي
     */
    private fun parsePlan(json: String): AgentGoal? {
        return try {
            val obj = JSONObject(json)
            val goalId = obj.getString("goal_id")
            val description = obj.getString("description")
            val stepsArray = obj.getJSONArray("steps")

            val steps = mutableListOf<PlanStep>()

            for (i in 0 until stepsArray.length()) {
                val stepObj = stepsArray.getJSONObject(i)
                val action = stepObj.getString("action")

                when (action) {
                    "click" -> {
                        val text = stepObj.optString("text", "")
                        if (text.isNotBlank()) steps.add(PlanStep.Click(text))
                    }
                    "scroll" -> {
                        steps.add(PlanStep.ScrollForward())
                    }
                    "wait" -> {
                        val text = stepObj.optString("text", "")
                        steps.add(PlanStep.WaitFor(text))
                    }
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
                if (start != -1 && end != -1) it.substring(start, end + 1) else it
            }
    }

    private fun buildPrompt(text: String, context: String): String {
        return if (context.isNotBlank()) {
            "السياق:\n$context\n\nالمستخدم:\n$text"
        } else {
            text
        }
    }
}
