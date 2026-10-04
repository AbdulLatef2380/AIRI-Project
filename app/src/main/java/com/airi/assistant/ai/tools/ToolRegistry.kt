package com.airi.assistant.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorRuntimeManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor
import com.airi.assistant.domain.customskill.CustomSkillRepository

class ToolRegistry(private val context: Context) {

    private val secureStorage = SecureStorage(context)
    private val customSkillRepository = CustomSkillRepository(context)

    fun getAvailableTools(): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (secureStorage.isGithubConnected()) {
            // Legacy task tools may read through the canonical runtime only;
            // credential flags do not grant an action or bypass its profile.
            tools.add(GithubGetUserTool(ServiceLocator.connectorRuntimeManager))
            tools.add(GithubGetReposTool(ServiceLocator.connectorRuntimeManager))
        }
        if (secureStorage.isGoogleConnected()) {
            // These tools are adapters over the registered GoogleConnector. They
            // must never become independent API clients or success-shaped stubs.
            tools.add(GmailListEmailsTool(ServiceLocator.connectorRuntimeManager))
            tools.add(DriveSearchFileTool(ServiceLocator.connectorRuntimeManager))
            tools.add(CalendarNextEventsTool(ServiceLocator.connectorRuntimeManager))
        }
        customSkillRepository.getAllSkills().forEach { skill ->
            tools.add(CustomSkillTool(context, skill))
        }
        return tools
    }

    fun getToolByName(name: String): Tool? =
        getAvailableTools().firstOrNull { it.name == name }

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
            "telegram_send_message" to ToolMeta(
                whenToUse = "When user explicitly asks to send a Telegram message to a specific chat or contact",
                expectedInput = "chat_id (Telegram chat ID or username), text (message content)"
            ),
            "gmail_list_emails" to ToolMeta(
                whenToUse = "When user asks to check, read, or list their Gmail emails",
                expectedInput = "max (optional): number of emails to return"
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
    override val description = "Get the authenticated GitHub user's profile info"
    override val parameters: Map<String, String> = emptyMap()

    override suspend fun execute(params: Map<String, String>): ToolResult =
        runtime.execute(
            "github",
            ConnectorInput(action = "status", authorizationActionId = "status")
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

// ─── Google: Gmail List ────────────────────────────────────────────────────────

private class GmailListEmailsTool(private val runtime: ConnectorRuntimeManager) : Tool {
    override val name = "gmail_list_emails"
    override val description = "List recent Gmail emails (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "max" to "max number of emails to return (default 5)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val maxResults = params["max"]?.toIntOrNull()?.coerceIn(1, 100) ?: 5
        return runtime.execute(
            "google",
            ConnectorInput(
                action = "gmail_list",
                params = mapOf("max_results" to maxResults.toString()),
                authorizationActionId = "gmail_list",
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
