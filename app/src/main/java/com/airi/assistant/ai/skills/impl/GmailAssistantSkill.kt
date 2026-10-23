package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.ConnectorSkillRuntime
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.integrations.google.GoogleDataScopes

class GmailAssistantSkill(private val context: Context) : AiriSkill {

    override val name = "gmail_assistant"
    override val description = "Read and summarize important emails from Gmail"
    override val parameters: Map<String, String> = mapOf(
        "query" to "the user's mailbox request, including latest/unread/sent/inbox intent",
        "max" to "maximum number of emails to return (default 5)"
    )

    private val exactPhrases = listOf(
        "latest email", "check email", "read emails", "my inbox", "check inbox"
    )
    private val singleWords = listOf(
        "email", "emails", "gmail", "inbox", "unread", "mail"
    )

    override fun score(input: String, context: SkillContext): Int {
        if (!hasGmailDataAccess()) return 0
        val lower = input.lowercase()
        var score = 0

        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach  { if (lower.contains(it)) score += 15 }

        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.let {
            it.contains("email") || it.contains("gmail") || it.contains("inbox")
        } == true) score += 10

        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!hasGmailDataAccess()) {
            return SkillResult(false, "", "Gmail data access is not authorized. Connect Gmail and grant its read permission in Integrations.")
        }

        val input = ((params["input"] as? String).orEmpty()).ifBlank {
            (params["query"] as? String).orEmpty()
        }
        val context = params["context"] as? SkillContext
        val max     = resolveMax(input, context)

        val list = ConnectorSkillRuntime.execute(
            connectorId = "google",
            action = "gmail_list",
            params = mapOf("max_results" to max.toString(), "query" to resolveQuery(input)),
        )
        if (!list.success) return list

        // The connector intentionally returns IDs only from list; message content
        // is fetched through the separately authorized read action.
        val messageIds = GmailMessageListParser.messageIds(list.data, max)
        if (messageIds.isEmpty()) {
            return SkillResult(true, "No matching Gmail messages found.", null, metadata = mapOf("execution_path" to "connector_runtime"))
        }

        val mailboxLabel = when {
            "sent" in resolveQuery(input) -> "sent"
            "inbox" in resolveQuery(input) -> "inbox"
            else -> "matching"
        }
        val messages = mutableListOf<String>()
        messageIds.forEachIndexed { index, messageId ->
            val read = ConnectorSkillRuntime.execute(
                connectorId = "google",
                action = "gmail_read",
                params = mapOf("message_id" to messageId),
            )
            if (!read.success) return read
            messages += "Message ${index + 1} ($messageId):\n${read.data}"
        }
        return SkillResult(
            success = true,
            data = "Latest $mailboxLabel Gmail messages (for summarization):\n" +
                messages.joinToString("\n\n").take(MAX_RESULT_CHARS),
            metadata = mapOf(
                "execution_path" to "connector_runtime",
                "mailbox" to mailboxLabel,
                "message_count" to messageIds.size.toString(),
            ),
        )
    }

    private fun hasGmailDataAccess(): Boolean = runCatching {
        ServiceLocator.googleAuthService.hasAuthorizedDataScopes(
            "google_gmail", GoogleDataScopes.scopesForSurface("google_gmail")
        )
    }.getOrDefault(false)

    private companion object { const val MAX_RESULT_CHARS = 12_000 }

    /** Preserve the user's mailbox intent instead of silently forcing `in:sent`. */
    private fun resolveQuery(input: String): String {
        val lower = input.lowercase()
        return when {
            "sent" in lower || "المرسلة" in lower || "ارسلت" in lower -> "in:sent"
            "unread" in lower || "غير مقروء" in lower || "غير مقروءة" in lower -> "is:unread"
            "inbox" in lower || "الوارد" in lower || "البريد الوارد" in lower -> "in:inbox"
            else -> "in:anywhere"
        }
    }

    private fun resolveMax(input: String, context: SkillContext?): Int {
        val numRegex = Regex("\\d+")
        val match = numRegex.find(input)
        if (match != null) return match.value.toIntOrNull()?.coerceIn(1, 20) ?: 5
        val lastMsg = context?.lastAssistantMessage ?: return 5
        val lastMatch = numRegex.find(lastMsg)
        return lastMatch?.value?.toIntOrNull()?.coerceIn(1, 20) ?: 5
    }
}
