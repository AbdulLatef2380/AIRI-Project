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
    private val llamaManager: LlamaManager
) {

    /**
     * المعالج المركزي: يقرر ما إذا كان الطلب دردشة عادية أم خطة تنفيذية
     */
    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        
        // 1️⃣ استخراج سياق الشاشة إذا لزم الأمر
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        // 2️⃣ فحص نية التنفيذ: إذا كان الأمر يحتوي على كلمات مفتاحية للتنفيذ
        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            
            val planPrompt = """
                You are an Android Task Planner. 
                Respond ONLY with a valid JSON object. No explanation.
                
                Context: $screenContext
                User Task: ${input.text}
                
                JSON Format:
                {
                  "goal_id": "unique_id",
                  "description": "action description",
                  "steps": [
                    {"action": "click", "text": "target_text"},
                    {"action": "scroll"}
                  ]
                }
            """.trimIndent()

            val rawJson = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            // تنظيف النص المستلم وتحويله إلى هدف (Goal)
            val cleanedJson = cleanRawJson(rawJson)
            val goal = parsePlan(cleanedJson)

            if (goal != null) {
                return@coroutineScope BrainOutput(
                    responseText = "🚀 تم إنشاء خطة تنفيذ: ${goal.description}",
                    executedGoalId = goal.id
                )
            } else {
                Log.e("AIRI_BRAIN", "Failed to parse plan. Raw output: $rawJson")
            }
        }

        // 3️⃣ المسار الافتراضي: محادثة عادية عبر LLM
        val enrichedPrompt = buildPrompt(input.text, screenContext)
        val response = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(enrichedPrompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }

        return@coroutineScope BrainOutput(responseText = response)
    }

    /**
     * تحويل نص JSON إلى كائن AgentGoal قابل للتنفيذ
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
            Log.e("AIRI_PARSER", "JSON Parsing Error: ${e.message}")
            null
        }
    }

    /**
     * تنظيف مخرجات الـ LLM من أي Markdown (مثل ```json) لضمان استقرار الـ Parser
     */
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
            "سياق الشاشة الحالي:\n$context\n\nطلب المستخدم:\n$text"
        } else {
            text
        }
    }
}
