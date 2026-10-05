package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.core.ServiceLocator

class GmailAssistantSkill(private val context: Context) : AiriSkill {

    override val name = "gmail_assistant"
    override val description = "Read and summarize important emails from Gmail"
    override val parameters: Map<String, String> = mapOf(
        "max" to "maximum number of emails to return (default 5)"
    )

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

        val output = ServiceLocator.connectorRuntimeManager.execute(
            connectorId = "google",
            input = ConnectorInput(action = "gmail_list", params = mapOf("max_results" to max.toString()))
        )
        return when (output) {
            is ConnectorOutput.Success -> SkillResult(true, output.text)
            is ConnectorOutput.Failure -> SkillResult(false, "", output.message)
            is ConnectorOutput.Streaming -> SkillResult(false, "", "Streaming output is not supported for Gmail.")
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
