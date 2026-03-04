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

    private val maxRetries = 2

    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {

        var attempt = 0
        var lastError: String? = null

        val screenContext = if (input.withContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        if (!isExecutionIntent(input.text)) {
            val response = requestChatResponse(input.text, screenContext)
            return@coroutineScope BrainOutput(response)
        }

        while (attempt <= maxRetries) {
            try {

                val rawResponse = requestPlan(input.text, screenContext)
                val cleanedJson = cleanRawJson(rawResponse)

                if (!cleanedJson.trim().startsWith("{")) {
                    throw Exception("Model did not return valid JSON")
                }

                val planDto = parseToDto(cleanedJson)
                    ?: throw Exception("Plan parsing failed")

                if (!PlanValidator.isValid(planDto)) {
                    throw Exception("Plan validation failed")
                }

                val goal = buildGoalFromDto(planDto)

                val success = withTimeoutOrNull(15000) {
                    goalExecutor.executeGoal(goal)
                } ?: return@coroutineScope BrainOutput("⏳ انتهى الوقت أثناء التنفيذ") 

                if (success) {
                    return@coroutineScope BrainOutput("✅ تم التنفيذ", goal)
                } else {
                    throw Exception("Field execution failed")
                }

            } catch (e: Throwable) {

                lastError = e.message
                attempt++

                if (attempt > maxRetries) {
                    break
                }

                Log.w("AIRI_RECOVERY", "Retry attempt $attempt بسبب: ${e.message}")

                delay(500) // small backoff before retry
            }
        }

        return@coroutineScope BrainOutput(
            message = "❌ فشل بعد $maxRetries محاولات: $lastError"
        )
    }

    private fun isExecutionIntent(text: String): Boolean {
        return text.contains("نفذ") ||
               text.contains("افتح") ||
               text.contains("اضغط")
    }

    private suspend fun requestPlan(text: String, context: String): String {
        val prompt = """
            أعد الرد JSON فقط.
            ممنوع أي نص إضافي.
            ممنوع markdown.
            {
              "steps": [
                { "id": "1", "action": "click" }
              ]
            }
            السياق: $context
            الأمر: $text
        """.trimIndent()

        return suspendCancellableCoroutine { cont ->
            llamaManager.generate(prompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
    }

    private suspend fun requestChatResponse(text: String, context: String): String {
        val prompt = if (context.isNotBlank())
            "Context:\n$context\nUser: $text"
        else text

        return suspendCancellableCoroutine { cont ->
            llamaManager.generate(prompt) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
    }

    private fun parseToDto(json: String): PlanDto? {
        return try {
            val obj = JSONObject(json)
            val stepsArray = obj.getJSONArray("steps")
            val stepsList = mutableListOf<StepDto>()

            for (i in 0 until stepsArray.length()) {
                val s = stepsArray.getJSONObject(i)
                val id = s.optString("id", i.toString())
                val action = s.getString("action")
                stepsList.add(StepDto(id, action))
            }

            PlanDto(stepsList)

        } catch (e: Exception) {
            Log.e("AIRI_BRAIN", "JSON Parsing Error: ${e.message}")
            null
        }
    }

    private fun buildGoalFromDto(dto: PlanDto): AgentGoal {
        val steps = dto.steps.map { step ->
            when (step.action) {
                "click" -> PlanStep.Click(step.id)
                "scroll" -> PlanStep.ScrollForward()
                "wait" -> PlanStep.WaitFor(step.id)
                else -> PlanStep.Click(step.id)
            }
        }

        val goalId = "goal_${System.currentTimeMillis()}"
        val description = "منفذة عبر AIRI"

        return AgentGoal(goalId, description, steps)
    }

    private fun cleanRawJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1)
            raw.substring(start, end + 1)
        else ""
    }
}
