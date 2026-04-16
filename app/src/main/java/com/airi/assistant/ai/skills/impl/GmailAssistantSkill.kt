package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.auth.SecureStorage

class GmailAssistantSkill(private val context: Context) : AiriSkill {

    override val name = "gmail_assistant"
    override val description = "Read and summarize important emails from Gmail"
    override val parameters: Map<String, String> = mapOf(
        "max" to "maximum number of emails to return (default 5)"
    )

    private val toolExecutor  = ToolExecutor(context)
    private val secureStorage = SecureStorage(context)

    private val exactPhrases = listOf(
        "latest email", "check email", "read emails", "my inbox", "check inbox"
    )
    private val singleWords = listOf(
        "email", "emails", "gmail", "inbox", "unread", "mail"
    )

    override fun score(input: String, context: SkillContext): Int {
        if (!secureStorage.isGoogleConnected()) return 0
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
        if (!secureStorage.isGoogleConnected()) {
            return SkillResult(false, "", "Google is not connected. Please connect it in Integrations.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext
        val max     = resolveMax(input, context)

        val r = toolExecutor.execute(ToolCall("gmail_list_emails", mapOf("max" to max.toString())))
        return SkillResult(r.success, r.data, r.error)
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
