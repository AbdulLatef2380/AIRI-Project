package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillRiskLevel
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.tools.execution.AlarmTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Reminder planning and one-shot alarm execution behind an explicit confirmation flag. */
class ReminderPlanningSkill(private val context: Context) : AiriSkill {
    private val alarms = AlarmTool(context)
    override val skillId = "reminder_planning"
    override val name = skillId
    override val displayName = "Reminder Planning"
    override val description = "Parse and schedule a one-shot reminder or timer with explicit confirmation"
    override val category = "PRODUCTIVITY"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess = SkillModelAccess.NONE
    override val parameters = mapOf(
        "input" to "string — reminder request",
        "confirmed" to "boolean — required for scheduling",
        "message" to "string — optional label"
    )
    override val inputSchema = parameters
    override val outputSchema = mapOf("scheduled" to "boolean", "time" to "string", "message" to "string")
    override val riskLevel = SkillRiskLevel.MEDIUM
    override val requiresConfirmation = true
    override val instructions = "First parse and preview the reminder. Schedule only when confirmed=true."
    override val examples = listOf("Remind me at 7:30pm to call Ahmed", "Set a timer for 20 minutes")
    override val limitations = listOf("Only one-shot alarms and timers are supported by this skill.", "Recurring reminders require a future typed reminder repository.")
    override val toolDefinitions = listOf(
        SkillToolDefinition(skillId, description, mapOf(
            "input" to SkillParamDef("string", "Natural-language reminder request"),
            "confirmed" to SkillParamDef("boolean", "Explicit user confirmation", false),
            "message" to SkillParamDef("string", "Reminder label", false)
        ), dangerous = true)
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        return (listOf("remind", "reminder", "alarm", "timer", "تذكير", "منبه", "مؤقت").count(lower::contains) * 25)
            .coerceIn(0, 100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult = withContext(Dispatchers.Main) {
        val input = params["input"] as? String ?: ""
        if (input.isBlank()) return@withContext failure("Reminder text is required.")
        val lower = input.lowercase()
        val message = (params["message"] as? String).orEmpty().ifBlank { input.take(120) }
        val confirmed = (params["confirmed"] as? String)?.toBooleanStrictOrNull()
            ?: (params["confirmed"] as? Boolean ?: false)
        val duration = alarms.parseDuration(input)
        if (duration != null && (lower.contains("timer") || lower.contains("مؤقت"))) {
            if (!confirmed) return@withContext preview("timer", "${duration}s", message)
            val result = alarms.setTimerViaIntent(duration, message)
            return@withContext SkillResult(result.success, result.message, result.message.takeIf { !result.success }, skillId, metadata = mapOf("scheduled" to result.success.toString(), "type" to "timer"))
        }
        val time = alarms.parseTime(input)
            ?: return@withContext failure("Could not parse a time. Use examples such as 7:30pm or 19:30.")
        if (!confirmed) return@withContext preview("alarm", "%02d:%02d".format(time.first, time.second), message)
        val result = alarms.setAlarmViaIntent(time.first, time.second, message)
        SkillResult(result.success, result.message, result.message.takeIf { !result.success }, skillId, metadata = mapOf("scheduled" to result.success.toString(), "type" to "alarm", "time" to "%02d:%02d".format(time.first, time.second)))
    }

    private fun preview(type: String, value: String, message: String) = SkillResult(
        true, "Preview: $type at $value — $message. Confirm explicitly to schedule.", skillName = skillId,
        metadata = mapOf("scheduled" to "false", "requires_confirmation" to "true", "type" to type, "value" to value)
    )

    private fun failure(message: String) = SkillResult(false, "", message, skillId)
}
