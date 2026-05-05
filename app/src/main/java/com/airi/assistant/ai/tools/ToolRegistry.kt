package com.airi.assistant.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.connector.ConnectorActionBridge
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.integrations.github.GithubService
import com.airi.assistant.integrations.telegram.TelegramService

class ToolRegistry(private val context: Context) {

    private val secureStorage = SecureStorage(context)
    private val githubService = GithubService(secureStorage)
    private val telegramService = TelegramService(secureStorage)
    private val customSkillRepository = CustomSkillRepository(context)

    fun getAvailableTools(): List<Tool> {
        val tools = mutableListOf<Tool>()
        if (secureStorage.isGithubConnected()) {
            tools.add(GithubGetUserTool(secureStorage, githubService))
            tools.add(GithubGetReposTool(secureStorage, githubService))
        }
        if (secureStorage.isTelegramConnected()) {
            tools.add(TelegramSendMessageTool(secureStorage, telegramService))
        }
        if (secureStorage.isGoogleConnected()) {
            tools.add(GmailListEmailsTool())
            tools.add(DriveSearchFileTool())
            tools.add(CalendarNextEventsTool())
        }
        customSkillRepository.getAllSkills().forEach { skill ->
            tools.add(CustomSkillTool(context, skill))
        }
        // ── System connector tools (always available — no auth required) ────────
        // These route through ConnectorActionBridge → ConnectorRegistry so any
        // connector that was registered at startup is reachable via the direct
        // tool-call format: {"tool":"read_file","params":{"path":"internal://..."}}
        tools.add(ReadFileTool())
        tools.add(WriteFileTool())
        tools.add(ListDirTool())
        tools.add(ExecTool())
        tools.add(HttpGetTool())
        tools.add(HttpPostTool())
        tools.add(BatteryStatusTool())
        tools.add(GetDeviceInfoTool())
        tools.add(LogcatReadTool())
        tools.add(GitStatusTool())
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
            ),
            // ── System connector tools ────────────────────────────────────────
            "read_file" to ToolMeta(
                whenToUse = "When user asks to read, view, or display the contents of a file on the device",
                expectedInput = "path: file path prefixed with internal://, cache://, or external://"
            ),
            "write_file" to ToolMeta(
                whenToUse = "When user asks to write, save, or create a file on the device",
                expectedInput = "path: file path (internal://...), content: text content to write"
            ),
            "list_dir" to ToolMeta(
                whenToUse = "When user asks to list, browse, or show files in a directory",
                expectedInput = "path: directory path (e.g. internal:// for app root)"
            ),
            "exec" to ToolMeta(
                whenToUse = "When user asks to run a shell command on the device (ls, cat, echo, ping, df, ps, curl, etc.)",
                expectedInput = "command: the shell command string to execute"
            ),
            "http_get" to ToolMeta(
                whenToUse = "When user asks to fetch a URL, check a website, or retrieve data from a REST API",
                expectedInput = "url: fully-qualified https:// URL"
            ),
            "http_post" to ToolMeta(
                whenToUse = "When user asks to send data to a REST API endpoint via HTTP POST",
                expectedInput = "url: endpoint URL, body: JSON body string"
            ),
            "battery_status" to ToolMeta(
                whenToUse = "When user asks about battery level, charge status, or power information",
                expectedInput = "No parameters required"
            ),
            "get_device_info" to ToolMeta(
                whenToUse = "When user asks about the device model, Android version, or hardware details",
                expectedInput = "No parameters required"
            ),
            "logcat_read" to ToolMeta(
                whenToUse = "When user asks to view recent system logs, debug output, or app errors",
                expectedInput = "lines (optional): number of log lines to return, default 50"
            ),
            "git_status" to ToolMeta(
                whenToUse = "When user asks about the git status of a repository on the device",
                expectedInput = "repo_path (optional): path to the git repo"
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

private class GithubGetUserTool(
    private val storage: SecureStorage,
    private val service: GithubService
) : Tool {
    override val name = "github_get_user"
    override val description = "Get the authenticated GitHub user's profile info"
    override val parameters: Map<String, String> = emptyMap()

    override suspend fun execute(params: Map<String, String>): ToolResult =
        service.getUser()
}

// ─── GitHub: Get Repos ────────────────────────────────────────────────────────

private class GithubGetReposTool(
    private val storage: SecureStorage,
    private val service: GithubService
) : Tool {
    override val name = "github_get_repos"
    override val description = "List the authenticated user's GitHub repositories"
    override val parameters: Map<String, String> = mapOf(
        "limit" to "max repos to return (optional, default 10)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val limit = params["limit"]?.toIntOrNull() ?: 10
        return service.getRepos(limit)
    }
}

// ─── Telegram: Send Message ────────────────────────────────────────────────────

private class TelegramSendMessageTool(
    private val storage: SecureStorage,
    private val service: TelegramService
) : Tool {
    override val name = "telegram_send_message"
    override val description = "Send a Telegram message to a chat via the connected bot"
    override val parameters: Map<String, String> = mapOf(
        "chat_id" to "Telegram chat ID or username",
        "text" to "Message text to send"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult {
        val chatId = params["chat_id"] ?: return ToolResult(false, "", "Missing chat_id parameter")
        val text = params["text"] ?: return ToolResult(false, "", "Missing text parameter")
        return service.sendMessage(chatId, text)
    }
}

// ─── Google: Gmail List ────────────────────────────────────────────────────────

private class GmailListEmailsTool : Tool {
    override val name = "gmail_list_emails"
    override val description = "List recent Gmail emails (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "max" to "max number of emails to return (default 5)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult =
        ToolResult(
            success = false,
            data = "",
            error = "Gmail API access requires a full OAuth access token. " +
                    "Re-connect Google and request offline access to enable this tool."
        )
}

// ─── Google: Drive Search ─────────────────────────────────────────────────────

private class DriveSearchFileTool : Tool {
    override val name = "drive_search_file"
    override val description = "Search for files in Google Drive (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "query" to "file name or search query"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult =
        ToolResult(
            success = false,
            data = "",
            error = "Drive API access requires a full OAuth access token. " +
                    "Re-connect Google and request offline access to enable this tool."
        )
}

// ─── Google: Calendar Events ──────────────────────────────────────────────────

private class CalendarNextEventsTool : Tool {
    override val name = "calendar_next_events"
    override val description = "Get upcoming Google Calendar events (requires OAuth access token)"
    override val parameters: Map<String, String> = mapOf(
        "count" to "number of upcoming events (default 5)"
    )

    override suspend fun execute(params: Map<String, String>): ToolResult =
        ToolResult(
            success = false,
            data = "",
            error = "Calendar API access requires a full OAuth access token. " +
                    "Re-connect Google and request offline access to enable this tool."
        )
}

// ═══════════════════════════════════════════════════════════════════════════
// System Connector Tools — route through ConnectorActionBridge
// ═══════════════════════════════════════════════════════════════════════════

private suspend fun connectorTool(
    action: String,
    params: Map<String, String>,
    text: String = "",
): ToolResult {
    val out = ConnectorActionBridge.dispatch(action = action, params = params, text = text)
        ?: return ToolResult(false, "", "Connector '$action' not available (registry not initialised)")
    return when (out) {
        is ConnectorOutput.Success   -> ToolResult(true,  out.text.ifBlank { "ok" }, null)
        is ConnectorOutput.Failure   -> ToolResult(false, "",                         out.message)
        is ConnectorOutput.Streaming -> ToolResult(true,  "streaming",                null)
    }
}

private class ReadFileTool : Tool {
    override val name        = "read_file"
    override val description = "Read text content from a file in the app's scoped storage"
    override val parameters  = mapOf("path" to "File path (internal://..., cache://..., external://...)")
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("read_file", params)
}

private class WriteFileTool : Tool {
    override val name        = "write_file"
    override val description = "Write text content to a file in the app's scoped storage (creates if missing)"
    override val parameters  = mapOf(
        "path"    to "File path (internal://..., cache://..., external://...)",
        "content" to "Text content to write"
    )
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("write_file", params, text = params["content"] ?: "")
}

private class ListDirTool : Tool {
    override val name        = "list_dir"
    override val description = "List files and subdirectories inside a directory"
    override val parameters  = mapOf("path" to "Directory path (e.g. internal:// for app root)")
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("list_dir", params)
}

private class ExecTool : Tool {
    override val name        = "exec"
    override val description = "Execute a sandboxed shell command (allowed: ls, cat, echo, grep, curl, ping, df, ps, uname, id, date, find, sort, wc, head, tail, tr)"
    override val parameters  = mapOf("command" to "Shell command string to execute")
    override suspend fun execute(params: Map<String, String>): ToolResult {
        val cmd = params["command"] ?: return ToolResult(false, "", "Missing 'command' parameter")
        return connectorTool("exec", params, text = cmd)
    }
}

private class HttpGetTool : Tool {
    override val name        = "http_get"
    override val description = "Perform an HTTP GET request and return the response body"
    override val parameters  = mapOf("url" to "Fully-qualified https:// URL to fetch")
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("http_get", params, text = params["url"] ?: "")
}

private class HttpPostTool : Tool {
    override val name        = "http_post"
    override val description = "Perform an HTTP POST request with a JSON body"
    override val parameters  = mapOf(
        "url"  to "Fully-qualified https:// endpoint URL",
        "body" to "JSON body string to send"
    )
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("http_post", params, text = params["body"] ?: "")
}

private class BatteryStatusTool : Tool {
    override val name        = "battery_status"
    override val description = "Get current battery level and charging status"
    override val parameters  = emptyMap<String, String>()
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("battery_status", emptyMap())
}

private class GetDeviceInfoTool : Tool {
    override val name        = "get_device_info"
    override val description = "Get device model, manufacturer, Android version, and hardware details"
    override val parameters  = emptyMap<String, String>()
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("get_device_info", emptyMap())
}

private class LogcatReadTool : Tool {
    override val name        = "logcat_read"
    override val description = "Read recent Android system log lines"
    override val parameters  = mapOf("lines" to "Number of log lines to return (default 50, max 200)")
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("logcat_read", params)
}

private class GitStatusTool : Tool {
    override val name        = "git_status"
    override val description = "Get the git working-tree status of a repository on the device"
    override val parameters  = mapOf("repo_path" to "Absolute path to the git repository (optional)")
    override suspend fun execute(params: Map<String, String>): ToolResult =
        connectorTool("git_status", params)
}
