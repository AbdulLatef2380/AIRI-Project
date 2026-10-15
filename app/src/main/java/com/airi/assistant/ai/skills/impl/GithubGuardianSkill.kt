package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.core.ServiceLocator

class GithubGuardianSkill(private val context: Context) : AiriSkill {

    override val name = "github_guardian"
    override val description = "Check GitHub repositories, pull requests, and profile status"
    override val parameters: Map<String, String> = mapOf(
        "action" to "get_user | get_repos | get_file",
        "limit"  to "number of repos to return (optional, default 10)",
        "repo"   to "owner/name repository (required for get_file)",
        "path"   to "repository-relative file path (required for get_file)"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name = "github_repository_read",
            description = "Read the authenticated GitHub profile, repositories, or a repository file",
            parameters = mapOf(
                "action" to SkillParamDef("string", "get_user, get_repos, or get_file", required = false),
                "limit" to SkillParamDef("int", "Maximum repositories to return", required = false),
                "repo" to SkillParamDef("string", "Repository in owner/name form", required = false),
                "path" to SkillParamDef("string", "Repository-relative file path", required = false),
            )
        )
    )

    private val toolExecutor  = ToolExecutor(context)

    private val exactPhrases = listOf(
        "check my github", "my github", "github status",
        "github profile", "code guardian"
    )
    private val singleWords = listOf(
        "github", "repos", "repositories", "pull request", "open source"
    )

    override fun score(input: String, context: SkillContext): Int {
        if (!isGitHubReady()) return 0
        val lower = input.lowercase()
        var score = 0

        exactPhrases.forEach { if (lower.contains(it)) score += 25 }
        singleWords.forEach  { if (lower.contains(it)) score += 15 }

        if (context.lastUsedSkill == name) score += 20
        if (context.lastAssistantMessage?.lowercase()?.contains("github") == true) score += 10

        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        if (!isGitHubReady()) {
            return SkillResult(false, "", "GitHub is not connected or ready in the connector runtime.")
        }

        val input   = params["input"] as? String ?: ""
        val context = params["context"] as? SkillContext
        val action  = params["action"] as? String ?: detectAction(input, context)

        return when (action) {
            "get_repos" -> {
                val limit = when (val raw = params["limit"]) {
                    is Number -> raw.toInt()
                    is String -> raw.toIntOrNull() ?: 10
                    else -> 10
                }
                val r = toolExecutor.execute(ToolCall("github_get_repos", mapOf("limit" to limit.toString())))
                SkillResult(r.success, r.data, r.error)
            }
            "get_file" -> {
                val repo = (params["repo"] as? String)
                    ?: extractRepository(input).first
                val path = (params["path"] as? String)
                    ?: extractRepository(input).second
                if (repo.isBlank() || path.isBlank()) {
                    return SkillResult(false, "", "A GitHub repository (owner/name) and file path are required.")
                }
                val r = toolExecutor.execute(
                    ToolCall("github_get_file", mapOf("repo" to repo, "path" to path))
                )
                SkillResult(r.success, r.data, r.error)
            }
            else -> {
                val r = toolExecutor.execute(ToolCall("github_get_user", emptyMap()))
                SkillResult(r.success, r.data, r.error)
            }
        }
    }

    private fun isGitHubReady(): Boolean = runCatching {
        ServiceLocator.connectorRegistry.get("github")?.state()?.value?.let { it.connected && it.healthy } ?: false
    }.getOrDefault(false)

    private fun detectAction(input: String, context: SkillContext?): String {
        val lower = input.lowercase()
        val lastMsg = context?.lastAssistantMessage?.lowercase() ?: ""
        if (lower.contains("github.com/") || lower.contains("get_file") ||
            lower.contains("read file") || lower.contains("اقرا الملف") || lower.contains("اقرأ الملف")) {
            return "get_file"
        }
        return if (lower.contains("repo") || lower.contains("repositories") ||
            lower.contains("projects") || lower.contains("list") ||
            lastMsg.contains("repo") || lastMsg.contains("project")
        ) "get_repos" else "get_user"
    }

    private fun extractRepository(input: String): Pair<String, String> {
        val match = Regex("github\\.com/([^/\\s]+)/([^/\\s#?]+)", RegexOption.IGNORE_CASE)
            .find(input) ?: return "" to ""
        val repo = match.groupValues[2].removeSuffix(".git")
        return "${match.groupValues[1]}/$repo" to "README.md"
    }
}
