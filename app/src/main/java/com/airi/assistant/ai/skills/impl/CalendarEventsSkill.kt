package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.ConnectorSkillRuntime
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.integrations.google.GoogleDataScopes

class CalendarEventsSkill(private val context: Context) : AiriSkill {

    override val name = "calendar_events"
    override val description = "Get upcoming events and schedule from Google Calendar"
    override val parameters: Map<String, String> = mapOf(
        "count" to "number of upcoming events to return (default 5)"
    )

    private val exactPhrases = listOf(
        "my schedule", "upcoming events", "my calendar",
        "check calendar", "today's events", "what's planned"
    )
    private val singleWords = listOf(
        "calendar", "schedule", "events", "agenda", "meetings"
    )

    override fun score(input: String, context: SkillContext): Int {
        if (!hasCalendarDataAccess()) return 0
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
        if (!hasCalendarDataAccess()) {
            return SkillResult(false, "", "Google Calendar data access is not authorized. Connect Calendar and grant its read permission in Integrations.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext
        val count   = resolveCount(input, context)

        return ConnectorSkillRuntime.execute(
            connectorId = "google",
            action = "calendar_list",
            params = mapOf("max_results" to count.toString()),
        )
    }

    private fun hasCalendarDataAccess(): Boolean = runCatching {
        ServiceLocator.googleAuthService.hasAuthorizedDataScopes(
            "google_calendar", GoogleDataScopes.scopesForSurface("google_calendar")
        )
    }.getOrDefault(false)

    private fun resolveCount(input: String, context: SkillContext?): Int {
        val numRegex = Regex("\\d+")
        val match = numRegex.find(input)
        if (match != null) return match.value.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val lastMsg = context?.lastAssistantMessage ?: return 5
        val lastMatch = numRegex.find(lastMsg)
        return lastMatch?.value?.toIntOrNull()?.coerceIn(1, 20) ?: 5
    }
}
