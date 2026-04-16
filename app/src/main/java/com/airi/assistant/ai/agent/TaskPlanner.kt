package com.airi.assistant.ai.agent

import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.auth.SecureStorage

class TaskPlanner(private val secureStorage: SecureStorage) {

    companion object {
        private val STEP_CONNECTORS = listOf(
            " then ", " and then ", " after that ", " afterwards ", " next then ",
            " ثم ", " وثم ", " وبعدها ", " ثم بعد ذلك ", " بعد ذلك "
        )

        private val TOOL_KEYWORDS: Map<String, List<String>> = mapOf(
            "github_get_user"        to listOf("github profile", "github user", "my github account", "who am i on github"),
            "github_get_repos"       to listOf("github repos", "my repos", "repositories", "github projects", "my projects"),
            "gmail_list_emails"      to listOf("email", "emails", "gmail", "inbox", "mail", "messages"),
            "telegram_send_message"  to listOf("telegram", "send message", "message via telegram", "via telegram", "through telegram"),
            "drive_search_file"      to listOf("drive", "google drive", "find file", "search file", "workspace", "document"),
            "calendar_next_events"   to listOf("calendar", "schedule", "events", "agenda", "meetings", "upcoming")
        )
    }

    fun plan(input: String, context: SkillContext): Task? {
        val lower = input.lowercase()
        val hasConnector = STEP_CONNECTORS.any { lower.contains(it) }
        if (!hasConnector) return null

        val steps = mutableListOf<TaskStep>()
        var remaining = lower

        for (connector in STEP_CONNECTORS) {
            if (!remaining.contains(connector)) continue
            val parts = remaining.split(connector, limit = 2)
            val firstSegment = parts[0].trim()
            remaining = parts.getOrElse(1) { "" }.trim()
            detectStep(firstSegment)?.let { steps.add(it) }
            if (remaining.isBlank()) break
        }

        if (remaining.isNotBlank()) {
            detectStep(remaining)?.let { steps.add(it) }
        }

        if (steps.size < 2) return null
        return Task(originalInput = input, steps = steps)
    }

    private fun detectStep(text: String): TaskStep? {
        for ((tool, keywords) in TOOL_KEYWORDS) {
            if (keywords.none { text.contains(it) }) continue
            if (!isToolAvailable(tool)) continue
            return TaskStep(
                toolName = tool,
                params = extractParams(tool, text),
                description = text.take(60)
            )
        }
        return null
    }

    private fun isToolAvailable(tool: String): Boolean = when {
        tool.startsWith("github")   -> secureStorage.isGithubConnected()
        tool.startsWith("telegram") -> secureStorage.isTelegramConnected()
        tool.startsWith("gmail") || tool.startsWith("drive") || tool.startsWith("calendar") ->
            secureStorage.isGoogleConnected()
        else -> true
    }

    private fun extractParams(tool: String, text: String): Map<String, String> = when (tool) {
        "github_get_repos"       -> mapOf("limit" to "10")
        "gmail_list_emails"      -> mapOf("max" to "5")
        "calendar_next_events"   -> mapOf("count" to "5")
        "drive_search_file" -> {
            val query = text
                .replace("find file", "").replace("search file", "")
                .replace("google drive", "").replace("drive", "")
                .replace("document", "").trim()
            mapOf("query" to query.ifBlank { "" })
        }
        "telegram_send_message" -> {
            val toIdx = text.indexOf(" to ")
            val chatId = if (toIdx >= 0) text.substring(toIdx + 4).trim().split(" ").firstOrNull() ?: "" else ""
            mapOf("chat_id" to chatId, "text" to "")
        }
        else -> emptyMap()
    }
}
