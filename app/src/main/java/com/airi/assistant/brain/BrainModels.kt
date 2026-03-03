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

        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            val planPrompt = """
                أنت محرك تخطيط أندرويد. أعد الرد JSON فقط.
                {
                  "steps": [ { "id": "1", "action": "click" } ]
                }
                السياق: $screenContext
                الأمر: ${input.text}
            """.trimIndent()

            val rawResponse = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            val cleanedJson = cleanRawJson(rawResponse)
            val planDto = parseToDto(cleanedJson)

            if (planDto == null || !PlanValidator.isValid(planDto)) {
                return@coroutineScope BrainOutput("⚠ الخطة مرفوضة: إما تالفة أو غير آمنة")
            }

            val goal = buildGoalFromDto(planDto)

            val success = withTimeoutOrNull(15000) {
                goalExecutor.executeGoal(goal)
            } ?: return@coroutineScope BrainOutput("⏳ انتهى الوقت أثناء التنفيذ")

            return@coroutineScope if (success) {
                BrainOutput("✅ تم التنفيذ: ${goal.description}", goal.id)
            } else {
                BrainOutput("❌ فشل التنفيذ الميداني", goal.id)
            }
        }

        val response = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(buildPrompt(input.text, screenContext)) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        return@coroutineScope BrainOutput(message = response)
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
        return if (start != -1 && end != -1) raw.substring(start, end + 1) else ""
    }

    private fun buildPrompt(text: String, context: String) =
        if (context.isNotBlank()) "Context:\n$context\nUser: $text" else text
}
