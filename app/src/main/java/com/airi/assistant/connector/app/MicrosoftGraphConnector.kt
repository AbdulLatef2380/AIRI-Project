package com.airi.assistant.connector.app

import com.airi.assistant.connector.*
import com.airi.assistant.connector.oauth.MicrosoftOAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Microsoft Graph vertical slice: signed-in identity, Outlook mail, and Calendar read. */
class MicrosoftGraphConnector(
    private val authManager: ConnectorAuthManager,
    private val configProvider: () -> OAuthConfiguration = MicrosoftOAuthConfiguration::current,
    private val tokenService: MicrosoftGraphTokenService = MicrosoftGraphTokenService(authManager, configProvider),
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : Connector {
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

    fun buildAuthUrl(): Result<String> {
        val config = configProvider() as? OAuthConfiguration.Configured
            ?: return Result.failure(IllegalStateException("Microsoft OAuth configuration is unavailable"))
        val request = OAuthStateRegistry.issuePkce(id)
        val scopes = listOf("openid", "profile", "email", "offline_access", "User.Read", "Mail.Read", "Calendars.Read")
            .joinToString(" ")
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
        if (request.connectorId != id || request.codeVerifier.isNullOrBlank()) return@withContext false
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("code_verifier", request.codeVerifier)
            .add("scope", "openid profile email offline_access User.Read Mail.Read Calendars.Read")
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
                authManager.storeToken(id, access, refresh, System.currentTimeMillis() + expiresIn * 1000L)
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
        return if (response.first in 200..299) {
            val json = JSONObject(response.second)
            update(true, "Connected as ${json.optString("mail").ifBlank { json.optString("userPrincipalName") }}")
        } else {
            if (response.first == 401) tokenService.clear()
            update(false, "Microsoft Graph health check failed (${response.first})")
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
            "outlook_mail_read" -> "/me/messages?\$top=10&\$select=id,subject,receivedDateTime,from"
            "outlook_calendar_read" -> "/me/calendar/events?\$top=10&\$select=id,subject,start,end,organizer"
            "status" -> "/me?\$select=id,displayName,mail,userPrincipalName"
            else -> return ConnectorOutput.Failure("unknown_action", "Unknown Microsoft Graph action: ${input.action}")
        }
        val response = graphGet(path, token)
        if (response.first == 401) {
            tokenService.clear()
            _state.value = ConnectorState(false, false, "Authorization expired")
            return ConnectorOutput.Failure("authorization_expired", "Microsoft authorization expired; reconnect is required", true)
        }
        if (response.first !in 200..299) return ConnectorOutput.Failure("provider_error", "Microsoft Graph request failed (${response.first})", response.first >= 500)
        return ConnectorOutput.Success(response.second, data = mapOf("provider" to "microsoft_graph", "action" to input.action))
    }

    override fun agentActions() = listOf(
        ConnectorAgentAction("outlook_mail_read", "Read recent mail from the signed-in Microsoft account"),
        ConnectorAgentAction("outlook_calendar_read", "Read upcoming calendar events from the signed-in Microsoft account"),
        ConnectorAgentAction("status", "Check Microsoft Graph connection status"),
    )

    private suspend fun graphGet(path: String, token: String): Pair<Int, String> = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(MicrosoftOAuthConfiguration.GRAPH_BASE_URL + path).header("Authorization", "Bearer $token").header("Accept", "application/json").build()).execute().use { response ->
            response.code to (response.body?.string() ?: "{}")
        }
    }

    private fun update(connected: Boolean, message: String): ConnectorState {
        val state = ConnectorState(connected, connected, message, System.currentTimeMillis(), if (connected) null else message)
        _state.value = state
        return state
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")
}
