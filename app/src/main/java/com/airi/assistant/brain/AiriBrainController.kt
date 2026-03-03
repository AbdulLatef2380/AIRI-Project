package com.airi.assistant.brain

import android.util.Log
import com.airi.assistant.accessibility.ScreenContextHolder
import com.airi.assistant.LlamaManager
import com.airi.core.chain.AgentGoal
import com.airi.core.chain.PlanStep
import kotlinx.coroutines.*
import org.json.JSONObject
import kotlin.coroutines.resume

// تعريف وسيط للبيانات الخام القادمة من JSON (يتوافق مع Validator)
data class PlanDto(
    val goal_id: String,
    val description: String,
    val steps: List<StepDto>
)
data class StepDto(val action: String, val text: String = "")

class AiriBrainController(
    private val llamaManager: LlamaManager,
    private val goalExecutor: GoalExecutor 
) {

    suspend fun handle(input: BrainInput): BrainOutput = coroutineScope {
        
        val screenContext = if (input.includeScreenContext) {
            ScreenContextHolder.serviceInstance?.extractScreenContext() ?: ""
        } else ""

        if (input.text.contains("نفذ") || input.text.contains("افتح") || input.text.contains("اضغط")) {
            
            val planPrompt = """
                أنت محرك تخطيط لنظام أندرويد. أعد الرد بصيغة JSON فقط.
                ممنوع أي نص إضافي أو markdown.
                {
                  "goal_id": "task_id",
                  "description": "وصف المهمة",
                  "steps": [ { "action": "click", "text": "زر" } ]
                }
                سياق الشاشة: $screenContext
                الأمر: ${input.text}
            """.trimIndent()

            val rawResponse = suspendCancellableCoroutine<String> { cont ->
                llamaManager.generate(planPrompt) { response ->
                    if (cont.isActive) cont.resume(response)
                }
            }

            // 1️⃣ تنظيف الرد
            val cleanedJson = cleanRawJson(rawResponse)
            if (!cleanedJson.startsWith("{")) {
                return@coroutineScope BrainOutput("⚠ الرد غير صالح (ليس JSON)")
            }

            // 2️⃣ تحويل إلى DTO وفحصه (حل المشكلة رقم 3)
            val planDto = parseToDto(cleanedJson)
            if (planDto == null || !PlanValidator.isValid(planDto)) {
                return@coroutineScope BrainOutput("⚠ الخطة غير آمنة أو تالفة وتم رفضها")
            }

            // 3️⃣ التحقق من الأوامر الحساسة (Guardian Check)
            if (planDto.description.contains("حذف") || planDto.description.contains("إعادة ضبط")) {
                return@coroutineScope BrainOutput("⚠ هذا الأمر حساس ويحتاج تأكيد يدوي")
            }

            // 4️⃣ تحويل الـ DTO الموثوق إلى AgentGoal للتنفيذ
            val goal = buildGoalFromDto(planDto)

            // 5️⃣ التنفيذ مع Timeout
            val success = withTimeoutOrNull(15000) {
                goalExecutor.executeGoal(goal)
            } ?: return@coroutineScope BrainOutput("⏳ انتهى الوقت أثناء التنفيذ")

            return@coroutineScope if (success) {
                BrainOutput("✅ تم التنفيذ بنجاح: ${goal.description}", goal.id)
            } else {
                BrainOutput("❌ فشل التنفيذ ميدانياً", goal.id)
            }
        }

        // المسار الافتراضي للمحادثة
        val response = suspendCancellableCoroutine<String> { cont ->
            llamaManager.generate(buildPrompt(input.text, screenContext)) { result ->
                if (cont.isActive) cont.resume(result)
            }
        }
        return@coroutineScope BrainOutput(responseText = response)
    }

    private fun parseToDto(json: String): PlanDto? {
        return try {
            val obj = JSONObject(json)
            val stepsArray = obj.getJSONArray("steps")
            val stepsList = mutableListOf<StepDto>()
            for (i in 0 until stepsArray.length()) {
                val s = stepsArray.getJSONObject(i)
                stepsList.add(StepDto(s.getString("action"), s.optString("text", "")))
            }
            PlanDto(obj.getString("goal_id"), obj.getString("description"), stepsList)
        } catch (e: Exception) { null }
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
        return AgentGoal(dto.goal_id, dto.description, steps)
    }

    private fun cleanRawJson(raw: String): String {
        val start = raw.indexOf("{")
        val end = raw.lastIndexOf("}")
        return if (start != -1 && end != -1) raw.substring(start, end + 1) else ""
    }

    private fun buildPrompt(text: String, context: String) = 
        if (context.isNotBlank()) "Context:\n$context\nUser: $text" else text
}
