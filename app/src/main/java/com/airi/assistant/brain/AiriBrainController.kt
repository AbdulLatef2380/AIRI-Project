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

        val screenContext = if (input.withContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        if (isExecutionIntent(input.text)) {

            val rawResponse = requestPlan(input.text, screenContext)

            val cleanedJson = cleanRawJson(rawResponse)

            if (!cleanedJson.trim().startsWith("{")) {
                return@coroutineScope BrainOutput("⚠ رد النموذج ليس JSON صالح")
            }

            val planDto = parseToDto(cleanedJson)

            if (planDto == null || !PlanValidator.isValid(planDto)) {
                return@coroutineScope BrainOutput("⚠ الخطة مرفوضة أو تالفة")
            }

            if (isSensitive(planDto.description)) {
                return@coroutineScope BrainOutput("⚠ أمر حساس يحتاج تأكيد")
            }

            val goal = buildGoalFromDto(planDto)

            val success = withTimeoutOrNull(15000) {
                goalExecutor.executeGoal(goal)
            } ?: return@coroutineScope BrainOutput("⏳ انتهى الوقت أثناء التنفيذ")

            return@coroutineScope if (success) {
                BrainOutput("✅ تم التنفيذ: ${goal.description}", goal)
            } else {
                BrainOutput("❌ فشل التنفيذ", goal)
            }
        }

        val response = requestChatResponse(input.text, screenContext)
        return@coroutineScope BrainOutput(response)
    }

    private fun isExecutionIntent(text: String): Boolean {
        return text.contains("نفذ") ||
               text.contains("افتح") ||
               text.contains("اضغط")
    }

    private fun isSensitive(description: String): Boolean {
        return description.contains("حذف") ||
               description.contains("إعادة ضبط")
    }

    private suspend fun requestPlan(text: String, context: String): String {
        val prompt = """
            أعد الرد JSON فقط.
            ممنوع أي نص إضافي.
            ممنوع markdown.
            {
              "goal_id": "task_1",
              "description": "وصف المهمة",
              "steps": [
                { "action": "click", "text": "نص" }
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
            val goalId = obj.getString("goal_id")
            val description = obj.getString("description")

            val stepsArray = obj.getJSONArray("steps")
            val steps = mutableListOf<StepDto>()

            for (i in 0 until stepsArray.length()) {
                val s = stepsArray.getJSONObject(i)
                steps.add(
                    StepDto(
                        action = s.getString("action"),
                        text = s.optString("text", "")
                    )
                )
            }

            PlanDto(goalId, description, steps)

        } catch (e: Exception) {
            Log.e("AIRI_BRAIN", "JSON Parse Error: ${e.message}")
            null
        }
    }

    private fun buildGoalFromDto(dto: PlanDto): AgentGoal {
        val steps = dto.steps.map {
            when (it.action) {
                "click" -> PlanStep.Click(it.text)
                "scroll" -> PlanStep.ScrollForward()
                "wait" -> PlanStep.WaitFor(it.text)
                else -> PlanStep.Click(it.text)
            }
        }

        return AgentGoal(
            id = dto.goal_id,
            description = dto.description,
            steps = steps
        )
    }

    private fun cleanRawJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1)
            raw.substring(start, end + 1)
        else ""
    }
}
