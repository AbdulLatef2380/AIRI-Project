package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.auth.SecureStorage

class CalendarEventsSkill(private val context: Context) : AiriSkill {

    override val name = "calendar_events"
    override val description = "Get upcoming events and schedule from Google Calendar"
    override val parameters: Map<String, String> = mapOf(
        "count" to "number of upcoming events to return (default 5)"
    )

    private val toolExecutor  = ToolExecutor(context)
    private val secureStorage = SecureStorage(context)

    private val exactPhrases = listOf(
        "my schedule", "upcoming events", "my calendar",
        "check calendar", "today's events", "what's planned"
    )
    private val singleWords = listOf(
        "calendar", "schedule", "events", "agenda", "meetings"
    )

    override fun score(input: String, context: SkillContext): Int {
        if (!secureStorage.isGoogleConnected()) return 0
        val lower = input.lowercase()
        var score = 0

        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach  { if (lower.contains(it)) score += 15 }

        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.let {
            it.contains("calendar") || it.contains("event") || it.contains("meeting")
        } == true) score += 10

        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!secureStorage.isGoogleConnected()) {
            return SkillResult(false, "", "Google is not connected. Please connect it in Integrations.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext
        val count   = resolveCount(input, context)

        val r = toolExecutor.execute(ToolCall("calendar_next_events", mapOf("count" to count.toString())))
        return SkillResult(r.success, r.data, r.error)
    }

    private fun resolveCount(input: String, context: SkillContext?): Int {
        val numRegex = Regex("\\d+")
        val match = numRegex.find(input)
        if (match != null) return match.value.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val lastMsg = context?.lastAssistantMessage ?: return 5
        val lastMatch = numRegex.find(lastMsg)
        return lastMatch?.value?.toIntOrNull()?.coerceIn(1, 20) ?: 5
    }
}
