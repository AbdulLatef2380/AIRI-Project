package com.airi.assistant.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorRuntimeManager
import com.airi.assistant.connector.ConnectorAccessDecision
import com.airi.assistant.connector.ConnectorAccessPolicy
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor
import com.airi.assistant.domain.customskill.CustomSkillRepository

class ToolRegistry(private val context: Context) {

    private val customSkillRepository = CustomSkillRepository(context)

    fun getAvailableTools(): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (isActionAvailable("github", "get_user")) {
            tools.add(GithubGetUserTool(ServiceLocator.connectorRuntimeManager))
        }
        if (isActionAvailable("github", "list_repos")) {
            tools.add(GithubGetReposTool(ServiceLocator.connectorRuntimeManager))
        }
        if (isActionAvailable("github", "get_file")) {
            tools.add(GithubGetFileTool(ServiceLocator.connectorRuntimeManager))
        }
        if (isActionAvailable("google", "gmail_list")) {
            tools.add(GmailListEmailsTool(ServiceLocator.connectorRuntimeManager))
        }
        if (isActionAvailable("google", "gmail_read")) {
            tools.add(GmailReadEmailTool(ServiceLocator.connectorRuntimeManager))
        }
        if (isActionAvailable("google", "drive_search")) {
            tools.add(DriveSearchFileTool(ServiceLocator.connectorRuntimeManager))
        }
        if (isActionAvailable("google", "calendar_list")) {
            tools.add(CalendarNextEventsTool(ServiceLocator.connectorRuntimeManager))
        }
        customSkillRepository.getAllSkills().forEach { skill ->
            tools.add(CustomSkillTool(context, skill))
        }
        return tools
    }

    fun getToolByName(name: String): Tool? =
        getAvailableTools().firstOrNull { it.name == name }

    private fun isActionAvailable(connectorId: String, actionId: String): Boolean = runCatching {
        val connector = ServiceLocator.connectorRegistry.get(connectorId) ?: return false
        val action = connector.agentActions().firstOrNull { it.id == actionId } ?: return false
        val state = connector.stateForAction(action)
        val profile = ServiceLocator.connectorAccessProfileStore.get(action.surfaceId ?: connectorId)
        state.connected && state.healthy &&
            ConnectorAccessPolicy.evaluate(profile, action) == ConnectorAccessDecision.ALLOWED
    }.getOrDefault(false)

    fun buildToolDescriptionBlock(): String {
        val tools = getAvailableTools()
        if (tools.isEmpty()) return ""
        return buildString {
            append("\n\nYou have access to the following real tools. Use them ONLY when the user asks for live data:")
            for (tool in tools) {
                val meta = TOOL_METADATA[tool.name]
                append("\n\n- Tool: ${tool.name}")
                append("\n  Description: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    val paramDesc = tool.parameters.entries.joinToString(", ") { "${it.key} (${it.value})" }
                    append("\n  Parameters: $paramDesc")
                }
                if (meta != null) {
                    append("\n  When to use: ${meta.whenToUse}")
                    append("\n  Expected input: ${meta.expectedInput}")
                } else if (tool is CustomSkillTool) {
                    append("\n  When to use: When the user's request aligns with the skill '${tool.skillName}' or its description.")
                    append("\n  Expected input: user_input (the user's natural language request)")
                }
            }
            append("\n\nTo call a tool, respond ONLY with this exact JSON format — no other text:")
            append("\n{\"tool\": \"<tool_name>\", \"params\": {\"<key>\": \"<value>\"}}")
            append("\nOtherwise respond normally.")
        }
    }

    fun getToolInfos(): List<Pair<String, String>> =
        getAvailableTools().map { tool ->
            tool.name to if (tool is CustomSkillTool) "Custom Skill" else tool.name.substringBefore("_").replaceFirstChar(Char::titlecase)
        }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private data class ToolMeta(val whenToUse: String, val expectedInput: String)

    private companion object {
        private val TOOL_METADATA = mapOf(
            "github_get_user" to ToolMeta(
                whenToUse = "When user asks about their GitHub profile, username, bio, or account info",
                expectedInput = "No parameters required"
            ),
            "github_get_repos" to ToolMeta(
                whenToUse = "When user asks to list, browse, or count their GitHub repositories",
                expectedInput = "limit (optional): max number of repos to return"
            ),
            "github_get_file" to ToolMeta(
                whenToUse = "When the agent needs to read a file from an authorized GitHub repository",
                expectedInput = "repo (owner/name) and path (repository-relative file path)"
            ),
            "gmail_list_emails" to ToolMeta(
                whenToUse = "When user asks to check, read, or list their Gmail emails",
                expectedInput = "max (optional): number of emails; query (optional): validated Gmail search query"
            ),
            "gmail_read_email" to ToolMeta(
                whenToUse = "When the agent needs the body of one authorized Gmail message",
                expectedInput = "message_id: Gmail message identifier"
            ),
            "drive_search_file" to ToolMeta(
                whenToUse = "When user wants to find a specific file or document in Google Drive",
                expectedInput = "query: file name or search terms"
            ),
            "calendar_next_events" to ToolMeta(
                whenToUse = "When user asks about upcoming meetings, schedule, or calendar events",
                expectedInput = "count (optional): number of events to return"
            )
        )
    }
}

fun customSkillToolName(skill: CustomSkill): String =
    "custom_skill_${skill.id.replace("-", "_")}"

internal class CustomSkillTool(
    context: Context,
    private val skill: CustomSkill
) : Tool {
    private val executor = CustomSkillExecutor(context)
    val skillName: String = skill.name
    override val name: String = customSkillToolName(skill)
    override val description: String = "Custom ${skill.type.name.lowercase()} skill: ${skill.description}"
    override val parameters: Map<String, String> = mapOf(
        "user_input" to "The user's request or message to send to this custom skill"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val result = executor.execute(skill, params)
        return ToolResult(result.success, result.data, result.error)
    }
}

// ─── GitHub: Get User ──────────────────────────────────────────────────────────

private class GithubGetUserTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "github_get_user"
    override val description = "Read the authenticated GitHub user's profile information"
    override val parameters: Map<String, String> = emptyMap()

    override suspend fun execute(params: Map<String, String>): ToolResult =
        runtime.execute(
            "github",
            ConnectorInput(action = "get_user", authorizationActionId = "get_user")
        ).toToolResult()
}

// ─── GitHub: Get Repos ────────────────────────────────────────────────────────

private class GithubGetReposTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "github_get_repos"
    override val description = "List the authenticated user's GitHub repositories"
    override val parameters: Map<String, String> = mapOf(
        "limit" to "max repos to return (optional, default 10)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val limit = params["limit"]?.toIntOrNull()?.coerceIn(1, 100) ?: 10
        return runtime.execute(
            "github",
            ConnectorInput(
                action = "list_repos",
                params = mapOf("limit" to limit.toString()),
                authorizationActionId = "list_repos",
            )
        ).toToolResult()
    }
}

// ─── GitHub: Get File ─────────────────────────────────────────────────────────

private class GithubGetFileTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "github_get_file"
    override val description = "Read a file from an authorized GitHub repository"
    override val parameters: Map<String, String> = mapOf(
        "repo" to "repository in owner/name form",
        "path" to "repository-relative file path"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val repo = params["repo"]?.trim().orEmpty()
        val path = params["path"]?.trim().orEmpty()
        if (!repo.matches(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))) {
            return ToolResult(false, "", "repo must use owner/name form")
        }
        if (path.isBlank() || path.length > 512 || path.startsWith("/")) {
            return ToolResult(false, "", "path must be a non-empty repository-relative path")
        }
        return runtime.execute(
            "github",
            ConnectorInput(
                action = "get_file",
                params = mapOf("repo" to repo, "path" to path),
                authorizationActionId = "get_file",
            )
        ).toToolResult()
    }
}

// ─── Google: Gmail List ────────────────────────────────────────────────────────

private class GmailListEmailsTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "gmail_list_emails"
    override val description = "List recent Gmail emails (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "max" to "max number of emails to return (default 5)",
        "query" to "validated Gmail search query (optional)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val maxResults = params["max"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        return runtime.execute(
            "google",
            ConnectorInput(
                action = "gmail_list",
                params = buildMap {
                    put("max_results", maxResults.toString())
                    params["query"]?.trim()?.takeIf { it.isNotBlank() }?.let { put("query", it) }
                },
                authorizationActionId = "gmail_list",
            )
        ).toToolResult()
    }
}

private class GmailReadEmailTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "gmail_read_email"
    override val description = "Read one authorized Gmail email body (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf("message_id" to "Gmail message identifier")

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val messageId = params["message_id"]?.trim().orEmpty()
        if (messageId.isBlank() || messageId.length > 512 || !messageId.matches(Regex("[A-Za-z0-9_-]+"))) {
            return ToolResult(false, "", "A valid Gmail message_id is required")
        }
        return runtime.execute(
            "google",
            ConnectorInput(
                action = "gmail_read",
                params = mapOf("message_id" to messageId),
                authorizationActionId = "gmail_read",
            )
        ).toToolResult()
    }
}

// ─── Google: Drive Search ─────────────────────────────────────────────────────

private class DriveSearchFileTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "drive_search_file"
    override val description = "Search for files in Google Drive (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "query" to "file name or search query"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult =
        runtime.execute(
            "google",
            ConnectorInput(action = "drive_search", params = params, authorizationActionId = "drive_search")
        ).toToolResult()
}

// ─── Google: Calendar Events ──────────────────────────────────────────────────

private class CalendarNextEventsTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "calendar_next_events"
    override val description = "Get upcoming Google Calendar events (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "count" to "number of upcoming events (default 5)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val maxResults = params["count"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        return runtime.execute(
            "google",
            ConnectorInput(
                action = "calendar_list",
                params = mapOf("max_results" to maxResults.toString()),
                authorizationActionId = "calendar_list",
            )
        ).toToolResult()
    }
}

private fun ConnectorOutput.toToolResult(): ToolResult = when (this) {
    is ConnectorOutput.Success -> ToolResult(true, text, null)
    is ConnectorOutput.Failure -> ToolResult(false, "", message)
    is ConnectorOutput.ApprovalRequired -> ToolResult(false, "", message)
    is ConnectorOutput.Streaming -> ToolResult(false, "", "Streaming output is not supported by this tool adapter")
}
