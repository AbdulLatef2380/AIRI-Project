package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.auth.SecureStorage

class GithubGuardianSkill(private val context: Context) : AiriSkill {

    override val name = "github_guardian"
    override val description = "Check GitHub repositories, pull requests, and profile status"
    override val parameters: Map<String, String> = mapOf(
        "action" to "get_user | get_repos",
        "limit"  to "number of repos to return (optional, default 10)"
    )

    private val toolExecutor  = ToolExecutor(context)
    private val secureStorage = SecureStorage(context)

    private val exactPhrases = listOf(
        "check my github", "my github", "github status",
        "github profile", "code guardian"
    )
    private val singleWords = listOf(
        "github", "repos", "repositories", "pull request", "open source"
    )

    override fun score(input: String, context: SkillContext): Int {
        if (!secureStorage.isGithubConnected()) return 0
        val lower = input.lowercase()
        var score = 0

        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach  { if (lower.contains(it)) score += 15 }

        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.contains("github") == true) score += 10

        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!secureStorage.isGithubConnected()) {
            return SkillResult(false, "", "GitHub is not connected. Please connect it in Integrations.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext
        val action  = params["action"] as? String ?: detectAction(input, context)

        return when (action) {
            "get_repos" -> {
                val limit = (params["limit"] as? String)?.toIntOrNull() ?: 10
                val r = toolExecutor.execute(ToolCall("github_get_repos", mapOf("limit" to limit.toString())))
                SkillResult(r.success, r.data, r.error)
            }
            else -> {
                val r = toolExecutor.execute(ToolCall("github_get_user", emptyMap()))
                SkillResult(r.success, r.data, r.error)
            }
        }
    }

    private fun detectAction(input: String, context: SkillContext?): String {
        val lower = input.lowercase()
        val lastMsg = context?.lastAssistantMessage?.lowercase() ?: ""
        return if (lower.contains("repo") || lower.contains("repositories") ||
            lower.contains("projects") || lower.contains("list") ||
            lastMsg.contains("repo") || lastMsg.contains("project")
        ) "get_repos" else "get_user"
    }
}
