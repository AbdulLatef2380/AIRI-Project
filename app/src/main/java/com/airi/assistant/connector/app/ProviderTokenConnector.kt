package com.airi.assistant.connector.app

import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorAgentAction
import com.airi.assistant.connector.ConnectorAgentParameter
import com.airi.assistant.connector.ConnectorAuthenticationType
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorProviderGrant
import com.airi.assistant.connector.ConnectorProviderGrantKind
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.connector.ConnectorAuthManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Small, deliberately read-only adapters for providers whose official token
 * APIs have a stable identity/read endpoint. Each catalog entry gets its own
 * instance and credential namespace; no provider is treated as connected from
 * token presence alone.
 */
class ProviderTokenConnector(
    private val config: Config,
    private val authManager: ConnectorAuthManager,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build(),
) : Connector {
    data class Config(
        val id: String,
        val name: String,
        val provider: String,
        val website: String,
        val documentation: String,
        val credentialLabel: String,
        val healthPath: String,
        val readPath: String?,
        val tokenHeader: (String) -> Pair<String, String>,
        val authType: ConnectorAuthenticationType,
        val tags: List<String>,
        val requestBody: String? = null,
    )

    override val id: String = config.id
    override val name: String = config.name
    override val description: String = "Read-only ${config.provider} data through the official provider API."
    override val type: ConnectorType = ConnectorType.APP
    private val state = MutableStateFlow(ConnectorState(false, false, "Not connected"))

    override fun meta(): ConnectorMeta = ConnectorMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        iconUrl = "${config.website}/favicon.ico",
        tags = config.tags,
        provider = config.provider,
        category = "Official integrations",
        authenticationType = config.authType,
        availability = com.airi.assistant.connector.ConnectorAvailability.READY,
        website = config.website,
        documentationUrl = config.documentation,
    )

    override fun state(): StateFlow<ConnectorState> = state.asStateFlow()

    override fun agentActions(): List<ConnectorAgentAction> = buildList {
        add(ConnectorAgentAction(
            id = "status",
            description = "Verify the authorized ${config.provider} identity and connection.",
            providerGrants = listOf(ConnectorProviderGrant(
                ConnectorProviderGrantKind.PROVIDER_ENDPOINT_PERMISSION,
                "A valid ${config.credentialLabel} accepted by the provider identity endpoint.",
            )),
        ))
        if (config.readPath != null) add(ConnectorAgentAction(
            id = "read",
            description = "Read the first bounded page of authorized ${config.provider} resources.",
            providerGrants = listOf(ConnectorProviderGrant(
                ConnectorProviderGrantKind.PROVIDER_ENDPOINT_PERMISSION,
                "Read permission for the configured ${config.provider} resource endpoint.",
            )),
            parameters = mapOf(
                "limit" to ConnectorAgentParameter(type = "int", description = "Maximum results from 1 to 50", minInt = 1, maxInt = 50),
            ),
        ))
    }

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        if (authManager.isExplicitlyDisconnected(id)) return@withContext update(false, "Disconnected")
        val token = authManager.getCredential(id, "token")?.trim()
        if (token.isNullOrBlank()) return@withContext update(false, "No ${config.credentialLabel}", "Enter a ${config.credentialLabel} to connect")
        try {
            val response = request(config.healthPath, token)
            if (response.code in 200..299 && (config.id != "slack" || response.json.optBoolean("ok", false))) {
                val identity = response.json.optString("username")
                    .ifBlank { response.json.optString("login") }
                    .ifBlank { response.json.optString("display_name") }
                    .ifBlank { response.json.optString("name") }
                    .ifBlank { "authorized account" }
                update(true, "Connected as $identity")
            } else update(false, "Provider rejected credentials (HTTP ${response.code})", "The ${config.provider} credential was rejected")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            update(false, "Connection check failed", "Could not reach ${config.provider}")
        }
    }

    override suspend fun disconnect() {
        val cleared = authManager.revokeToken(id) && authManager.clearCredential(id, "token") && authManager.setExplicitlyDisconnected(id, true)
        update(false, if (cleared) "Disconnected" else "Disconnected; credential cleanup needs attention")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (!state.value.connected) return@withContext ConnectorOutput.Failure("not_connected", "${config.provider} is disconnected")
        val token = authManager.getCredential(id, "token")
            ?: return@withContext ConnectorOutput.Failure("not_connected", "${config.credentialLabel} is not configured")
        val path = when (input.action) {
            "status" -> config.healthPath
            "read" -> config.readPath ?: return@withContext ConnectorOutput.Failure("unsupported_action", "${config.provider} has no read operation configured")
            else -> return@withContext ConnectorOutput.Failure("unknown_action", "Unknown action '${input.action}'")
        }
        val boundedPath = if (input.action == "read") appendLimit(path, input.params["limit"]?.toIntOrNull()?.coerceIn(1, 50) ?: 20) else path
        try {
            val response = request(boundedPath, token)
            when {
                response.code == 401 || response.code == 403 -> {
                    update(false, "Authorization expired", "${config.provider} rejected the authorized request")
                    ConnectorOutput.Failure("authorization_required", "Reconnect ${config.provider}")
                }
                response.code == 429 -> ConnectorOutput.Failure("rate_limited", "${config.provider} rate limit reached", true)
                response.code !in 200..299 -> ConnectorOutput.Failure("provider_error", "${config.provider} request failed (HTTP ${response.code})")
                response.code in 200..299 && config.id == "slack" && !response.json.optBoolean("ok", false) -> ConnectorOutput.Failure("authorization_required", "Reconnect Slack")
                else -> ConnectorOutput.Success(response.body, data = mapOf("provider" to config.provider, "action" to input.action))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            ConnectorOutput.Failure("network_error", "${config.provider} request could not be completed", true)
        }
    }

    private data class Response(val code: Int, val body: String, val json: JSONObject)

    private fun request(path: String, token: String): Response {
        val (header, value) = config.tokenHeader(token)
        val builder = Request.Builder().url(path).header(header, value).header("Accept", "application/json")
        val request = config.requestBody?.let {
            builder.post(it.toRequestBody("application/json".toMediaType())).build()
        } ?: builder.build()
        return http.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty().take(MAX_RESPONSE_CHARS)
            Response(response.code, body, runCatching { JSONObject(body) }.getOrElse { JSONObject() })
        }
    }

    private fun appendLimit(path: String, limit: Int): String = when {
        path.contains("?") -> "$path&per_page=$limit"
        else -> "$path?per_page=$limit"
    }

    private fun update(connected: Boolean, status: String, error: String? = null): ConnectorState {
        val next = ConnectorState(connected, connected, status, System.currentTimeMillis(), error)
        state.value = next
        return next
    }

    companion object {
        private const val MAX_RESPONSE_CHARS = 256 * 1024
        fun configs(): List<Config> = listOf(
            Config("gitlab", "GitLab", "GitLab", "https://gitlab.com", "https://docs.gitlab.com/api/user/", "GitLab personal access token", "https://gitlab.com/api/v4/user", "https://gitlab.com/api/v4/projects", { "PRIVATE-TOKEN" to it }, ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, listOf("git", "code", "projects")),
            Config("linear", "Linear", "Linear", "https://linear.app", "https://linear.app/developers/graphql", "Linear API key", "https://api.linear.app/graphql", null, { "Authorization" to it }, ConnectorAuthenticationType.API_KEY, listOf("issues", "projects"), requestBody = "{\"query\":\"{ viewer { id name } }\"}"),
            Config("discord", "Discord", "Discord", "https://discord.com", "https://discord.com/developers/docs/resources/user", "Discord bot token", "https://discord.com/api/v10/users/@me", "https://discord.com/api/v10/users/@me/guilds", { "Authorization" to "Bot $it" }, ConnectorAuthenticationType.API_KEY, listOf("chat", "communities")),
            Config("asana", "Asana", "Asana", "https://asana.com", "https://developers.asana.com/reference/users-me", "Asana personal access token", "https://app.asana.com/api/1.0/users/me", "https://app.asana.com/api/1.0/users/me", { "Authorization" to "Bearer $it" }, ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, listOf("tasks", "projects")),
            Config("todoist", "Todoist", "Todoist", "https://todoist.com", "https://developer.todoist.com/rest/v2/", "Todoist API token", "https://api.todoist.com/api/v1/user", "https://api.todoist.com/rest/v2/tasks", { "Authorization" to "Bearer $it" }, ConnectorAuthenticationType.API_KEY, listOf("tasks", "todo")),
            Config("slack", "Slack", "Slack", "https://slack.com", "https://api.slack.com/methods/auth.test", "Slack OAuth access token", "https://slack.com/api/auth.test", "https://slack.com/api/conversations.list", { "Authorization" to "Bearer $it" }, ConnectorAuthenticationType.API_KEY, listOf("chat", "messaging")),
            Config("figma", "Figma", "Figma", "https://www.figma.com", "https://www.figma.com/developers/api#auth", "Figma personal access token", "https://api.figma.com/v1/me", null, { "X-Figma-Token" to it }, ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN, listOf("design", "files")),
        )
    }
}
