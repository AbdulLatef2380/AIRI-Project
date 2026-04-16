package com.airi.assistant.ai.intent

data class SkillCall(
    val skillName: String,
    val params: Map<String, Any>
)

object SkillParser {

    private val patterns: Map<String, List<String>> = mapOf(
        "github_guardian" to listOf(
            "check my github", "my github", "my repos", "my repositories",
            "github status", "github profile", "show my repos", "list repos",
            "github projects", "open source projects"
        ),
        "telegram_messenger" to listOf(
            "send message on telegram", "telegram message", "send telegram",
            "message via telegram", "send on telegram", "via telegram",
            "through telegram", "send to telegram"
        ),
        "gmail_assistant" to listOf(
            "latest email", "my emails", "check email", "unread emails",
            "show emails", "gmail", "my inbox", "check inbox", "read emails"
        ),
        "drive_search" to listOf(
            "search in drive", "find file in drive", "google drive",
            "my files on drive", "workspace files", "search drive",
            "find in drive"
        ),
        "calendar_events" to listOf(
            "my schedule", "upcoming events", "my calendar",
            "what's on my calendar", "next events", "my agenda",
            "what's planned", "check calendar", "today's events"
        )
    )

    fun parse(input: String): SkillCall? {
        val lower = input.lowercase().trim()
        for ((skillName, triggers) in patterns) {
            if (triggers.any { lower.contains(it) }) {
                return SkillCall(skillName, extractParams(skillName, lower, input))
            }
        }
        return null
    }

    private fun extractParams(skillName: String, lower: String, original: String): Map<String, Any> {
        return when (skillName) {
            "github_guardian" -> {
                val action = when {
                    lower.contains("repo") || lower.contains("repositories") || lower.contains("projects") -> "get_repos"
                    else -> "get_user"
                }
                mapOf("action" to action)
            }
            "drive_search" -> {
                val cleaned = lower
                    .replace("search in drive", "")
                    .replace("find file in drive", "")
                    .replace("google drive", "")
                    .replace("my files on drive", "")
                    .replace("workspace files", "")
                    .replace("search drive", "")
                    .replace("find in drive", "")
                    .trim()
                if (cleaned.isNotBlank()) mapOf("query" to cleaned) else emptyMap()
            }
            "telegram_messenger" -> {
                val toSplit = lower.split(" to ")
                val textPart = if (toSplit.size >= 2) toSplit.last().trim() else ""
                val chatId = if (toSplit.size >= 2) toSplit[toSplit.size - 2]
                    .replace("send message on telegram", "")
                    .replace("telegram message", "")
                    .replace("send on telegram", "")
                    .replace("via telegram", "")
                    .trim() else ""
                buildMap {
                    if (chatId.isNotBlank()) put("chat_id", chatId)
                    if (textPart.isNotBlank()) put("text", textPart)
                }
            }
            else -> emptyMap()
        }
    }
}
