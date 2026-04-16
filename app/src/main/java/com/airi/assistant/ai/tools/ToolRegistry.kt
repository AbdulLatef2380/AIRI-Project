package com.airi.assistant.ai.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.integrations.github.GithubService
import com.airi.assistant.integrations.telegram.TelegramService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ToolRegistry(private val context: Context) {

    private val secureStorage = SecureStorage(context)
    private val githubService = GithubService(secureStorage)
    private val telegramService = TelegramService(secureStorage)

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
                append("\n- ${tool.name}: ${tool.description}")
                if (tool.parameters.isNotEmpty()) {
                    val paramDesc = tool.parameters.entries.joinToString(", ") { "${it.key} (${it.value})" }
                    append("\n  Params: $paramDesc")
                }
            }
            append("\n\nTo call a tool, respond ONLY with this exact JSON format — no other text:")
            append("\n{\"tool\": \"<tool_name>\", \"params\": {\"<key>\": \"<value>\"}}")
            append("\nOtherwise respond normally.")
        }
    }

    fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return false
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
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
