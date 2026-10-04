package com.airi.assistant.connector.app

import com.airi.assistant.connector.*
import com.airi.assistant.connector.oauth.MicrosoftOAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Microsoft Graph vertical slice: identity, Outlook, Calendar, OneDrive, and Teams read. */
class MicrosoftGraphConnector(
    private val authManager: ConnectorAuthManager,
    private val configProvider: () -> OAuthConfiguration = MicrosoftOAuthConfiguration::current,
    private val tokenService: MicrosoftGraphTokenService = MicrosoftGraphTokenService(authManager, configProvider),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : Connector {
    private data class GraphResponse(val code: Int, val body: String, val retryAfter: String?)
    override val id: String = MicrosoftOAuthConfiguration.CONNECTOR_ID
    override val name: String = "Microsoft Outlook & Calendar"
    override val description: String = "Read the signed-in Microsoft mailbox and calendar through Microsoft Graph."
    override val type: ConnectorType = ConnectorType.APP
    private val _state = kotlinx.coroutines.flow.MutableStateFlow(ConnectorState(false, false, "Not connected"))

    override fun meta() = ConnectorMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        provider = "Microsoft",
        category = "Microsoft",
        authenticationType = ConnectorAuthenticationType.OAUTH2,
        capabilities = listOf(
            ConnectorCapability("outlook.mail.read", "Read the signed-in user's mail"),
            ConnectorCapability("outlook.calendar.read", "Read the signed-in user's calendar"),
        ),
        tags = listOf("microsoft", "outlook", "calendar", "graph"),
    )

    override fun state() = _state.asStateFlow()

    fun oauthConfiguration(): OAuthConfiguration = configProvider()

    fun buildAuthUrl(actionScopes: Set<String>): Result<String> {
        val config = configProvider() as? OAuthConfiguration.Configured
            ?: return Result.failure(IllegalStateException("Microsoft OAuth configuration is unavailable"))
        val declaredActionScopes = setOf(
            ConnectorProviderScopes.MICROSOFT_MAIL_READ_BASIC,
            ConnectorProviderScopes.MICROSOFT_CALENDARS_READ_BASIC,
            ConnectorProviderScopes.MICROSOFT_FILES_READ,
            ConnectorProviderScopes.MICROSOFT_TEAM_READ_BASIC_ALL,
            ConnectorProviderScopes.MICROSOFT_USER_READ,
        )
        if (actionScopes.isEmpty() || actionScopes.any { it !in declaredActionScopes }) {
            return Result.failure(IllegalArgumentException("Microsoft authorization contains no granted action scope or an undeclared scope"))
        }
        val requestedScopes = ConnectorProviderScopes.microsoftAuthorizationScopes(actionScopes)
        val request = OAuthStateRegistry.issuePkce(id, requestedScopes)
        val scopes = requestedScopes.sorted().joinToString(" ")
        val url = buildString {
            append("https://login.microsoftonline.com/${config.tenant}/oauth2/v2.0/authorize?")
            append("client_id=${enc(config.clientId)}")
            append("&response_type=code")
            append("&redirect_uri=${enc(config.redirectUri)}")
            append("&response_mode=query")
            append("&scope=${enc(scopes)}")
            append("&state=${enc(request.state)}")
            append("&code_challenge=${enc(request.codeChallenge)}")
            append("&code_challenge_method=S256")
            append("&prompt=select_account")
        }
        return Result.success(url)
    }

    suspend fun handleCallback(code: String, request: OAuthStateRegistry.ConsumedRequest): Boolean = withContext(Dispatchers.IO) {
        val config = configProvider() as? OAuthConfiguration.Configured ?: return@withContext false
        if (request.connectorId != id || request.codeVerifier.isNullOrBlank() || request.requestedScopes.isEmpty()) return@withContext false
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("code_verifier", request.codeVerifier)
            .add("scope", request.requestedScopes.sorted().joinToString(" "))
            .build()
        runCatching {
            http.newCall(
                Request.Builder()
                    .url("https://login.microsoftonline.com/${config.tenant}/oauth2/v2.0/token")
                    .post(body)
                    .build()
            ).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val access = json.optString("access_token")
                if (!response.isSuccessful || access.isBlank()) return@use false
                val refresh = json.optString("refresh_token").ifBlank { null }
                val expiresIn = json.optLong("expires_in", 3600L).coerceIn(60L, 86_400L)
                authManager.storeToken(
                    id,
                    access,
                    refresh,
                    System.currentTimeMillis() + expiresIn * 1000L,
                    request.requestedScopes,
                )
            }
        }.getOrDefault(false)
    }

    override suspend fun connect(): ConnectorState {
        val result = tokenService.accessToken()
        if (result.isFailure) {
            val message = result.exceptionOrNull()?.message ?: "Microsoft authorization is required"
            return update(false, message)
        }
        val response = graphGet("/me?\$select=id,displayName,mail,userPrincipalName", result.getOrThrow())
        return if (response.code in 200..299) {
            val json = JSONObject(response.body)
            update(true, "Connected as ${json.optString("mail").ifBlank { json.optString("userPrincipalName") }}")
        } else {
            if (response.code == 401) tokenService.clear()
            update(false, "Microsoft Graph health check failed (${response.code})")
        }
    }

    override suspend fun disconnect() {
        tokenService.clear()
        _state.value = ConnectorState(false, false, "Disconnected")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        if (!_state.value.connected) return ConnectorOutput.Failure("not_connected", "Microsoft authorization is required", true)
        val token = tokenService.accessToken().getOrElse {
            _state.value = ConnectorState(false, false, "Authorization expired", errorMessage = "Microsoft authorization expired")
            return ConnectorOutput.Failure("authorization_expired", "Microsoft authorization expired; reconnect is required", true)
        }
        val path = when (input.action) {
            "outlook_mail_read" -> "/me/messages?\$top=${boundedTop(input.params["top"])}&\$select=id,subject,receivedDateTime,from"
            "outlook_calendar_read" -> "/me/calendar/events?\$top=${boundedTop(input.params["top"])}&\$select=id,subject,start,end,organizer"
            "onedrive_files_read" -> {
                val top = input.params["top"].orEmpty().toIntOrNull()?.coerceIn(1, 50) ?: 20
                val folderPath = input.params["folder_path"]?.trim()?.trim('/')
                val resource = if (folderPath.isNullOrBlank()) {
                    "/me/drive/root/children"
                } else {
                    "/me/drive/root:/${encPath(folderPath)}:/children"
                }
                "$resource?\$top=$top&\$select=id,name,size,folder,file,lastModifiedDateTime,webUrl"
            }
            "teams_list_joined" -> "/me/joinedTeams?\$select=id,displayName,description,visibility,webUrl"
            "status" -> "/me?\$select=id,displayName,mail,userPrincipalName"
            else -> return ConnectorOutput.Failure("unknown_action", "Unknown Microsoft Graph action: ${input.action}")
        }
        val response = graphGet(path, token)
        if (response.code == 401) {
            tokenService.clear()
            _state.value = ConnectorState(false, false, "Authorization expired")
            return ConnectorOutput.Failure("authorization_expired", "Microsoft authorization expired; reconnect is required", true)
        }
        if (response.code !in 200..299) return providerFailure(response)
        return ConnectorOutput.Success(response.body, data = mapOf("provider" to "microsoft_graph", "action" to input.action))
    }

    override fun agentActions() = listOf(
        ConnectorAgentAction("outlook_mail_read", "Read recent mail from the signed-in Microsoft account", surfaceId = "microsoft_outlook", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.MICROSOFT_MAIL_READ_BASIC)
        ), parameters = mapOf("top" to ConnectorAgentParameter(description = "Optional number of messages from 1 to 50."))),
        ConnectorAgentAction("outlook_calendar_read", "Read upcoming calendar events from the signed-in Microsoft account", surfaceId = "microsoft_calendar", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.MICROSOFT_CALENDARS_READ_BASIC)
        ), parameters = mapOf("top" to ConnectorAgentParameter(description = "Optional number of events from 1 to 50."))),
        ConnectorAgentAction(
            "onedrive_files_read",
            "List authorized OneDrive files in the root or a folder.",
            surfaceId = "microsoft_onedrive",
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.MICROSOFT_FILES_READ)),
            parameters = mapOf(
                "folder_path" to ConnectorAgentParameter(description = "Optional OneDrive folder path relative to the root."),
                "top" to ConnectorAgentParameter(description = "Optional number of items from 1 to 50."),
            ),
        ),
        ConnectorAgentAction("teams_list_joined", "List Microsoft Teams joined by the signed-in work or school account.", surfaceId = "microsoft_teams", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.MICROSOFT_TEAM_READ_BASIC_ALL)
        )),
        ConnectorAgentAction("status", "Check Microsoft Graph connection status", surfaceId = "microsoft_outlook", providerGrants = listOf(
            ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.MICROSOFT_USER_READ)
        )),
    )

    private suspend fun graphGet(path: String, token: String): GraphResponse = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(MicrosoftOAuthConfiguration.GRAPH_BASE_URL + path).header("Authorization", "Bearer $token").header("Accept", "application/json").build()).execute().use { response ->
            GraphResponse(response.code, response.body?.string() ?: "{}", response.header("Retry-After"))
        }
    }

    private fun providerFailure(response: GraphResponse): ConnectorOutput.Failure = when {
        response.code == 403 -> ConnectorOutput.Failure("permission_denied", "Microsoft Graph denied this capability", false)
        response.code == 429 -> ConnectorOutput.Failure("rate_limited", "Microsoft Graph rate limit reached${response.retryAfter?.let { "; retry after $it" }.orEmpty()}", true)
        response.code >= 500 -> ConnectorOutput.Failure("provider_unavailable", "Microsoft Graph is temporarily unavailable (${response.code})", true)
        else -> ConnectorOutput.Failure("provider_error", "Microsoft Graph request failed (${response.code})", false)
    }

    private fun boundedTop(raw: String?): Int = raw?.toIntOrNull()?.coerceIn(1, 50) ?: 10

    private fun update(connected: Boolean, message: String): ConnectorState {
        val state = ConnectorState(connected, connected, message, System.currentTimeMillis(), if (connected) null else message)
        _state.value = state
        return state
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    private fun encPath(value: String): String = value.split('/').joinToString("/") { enc(it).replace("+", "%20") }
}
