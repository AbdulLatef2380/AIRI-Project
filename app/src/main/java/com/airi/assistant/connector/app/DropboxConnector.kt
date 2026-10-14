package com.airi.assistant.connector.app

import com.airi.assistant.connector.*
import com.airi.assistant.connector.oauth.DropboxOAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Dropbox user-linked connector.
 *
 * The mobile flow is authorization-code + PKCE. No Dropbox client secret is
 * embedded in the APK. Tokens and the exact granted scope set are encrypted by
 * ConnectorAuthManager. Mutating file actions are intentionally not exposed in
 * this first verified slice.
 */
class DropboxConnector(
    private val authManager: ConnectorAuthManager,
    private val configProvider: () -> OAuthConfiguration = DropboxOAuthConfiguration::current,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : Connector {
    companion object {
        const val CONNECTOR_ID = "dropbox"
        const val ACCOUNT_READ = ConnectorProviderScopes.DROPBOX_ACCOUNT_INFO_READ
        const val METADATA_READ = ConnectorProviderScopes.DROPBOX_FILES_METADATA_READ
        const val CONTENT_READ = ConnectorProviderScopes.DROPBOX_FILES_CONTENT_READ
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private data class ApiResponse(val code: Int, val body: String)

    override val id: String = CONNECTOR_ID
    override val name: String = "Dropbox"
    override val description: String = "Read the signed-in user's Dropbox account and authorized files."
    override val type: ConnectorType = ConnectorType.APP
    private val _state = MutableStateFlow(ConnectorState(false, false, "Not connected"))

    override fun meta() = ConnectorMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        provider = "Dropbox",
        category = "Files",
        authenticationType = ConnectorAuthenticationType.OAUTH2,
        capabilities = listOf(
            ConnectorCapability("account.read", "Read the authorized Dropbox account"),
            ConnectorCapability("files.metadata.read", "List, search, and inspect authorized file metadata"),
            ConnectorCapability("files.content.read", "Resolve a temporary link for an authorized file"),
        ),
        availability = ConnectorAvailability.PARTIAL,
        website = "https://www.dropbox.com",
        documentationUrl = "https://docs.dropboxapi.com/dropbox-api/docs/oauth",
        tags = listOf("dropbox", "files", "storage"),
    )

    override fun state() = _state.asStateFlow()

    fun oauthConfiguration(): OAuthConfiguration = configProvider()

    fun buildAuthUrl(actionScopes: Set<String>): Result<String> {
        val config = configProvider() as? OAuthConfiguration.Configured
            ?: return Result.failure(IllegalStateException("Dropbox OAuth client id is not configured"))
        val allowed = setOf(ACCOUNT_READ, METADATA_READ, CONTENT_READ)
        if (actionScopes.isEmpty() || actionScopes.any { it !in allowed }) {
            return Result.failure(IllegalArgumentException("Dropbox authorization contains no declared scope or an undeclared scope"))
        }
        val request = OAuthStateRegistry.issuePkce(id, actionScopes)
        val query = linkedMapOf(
            "client_id" to config.clientId,
            "response_type" to "code",
            "redirect_uri" to config.redirectUri,
            "state" to request.state,
            "code_challenge" to request.codeChallenge,
            "code_challenge_method" to "S256",
            "token_access_type" to "offline",
            "scope" to actionScopes.sorted().joinToString(" "),
        )
        return Result.success(DropboxOAuthConfiguration.AUTHORIZE_URL + "?" + query.entries.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" })
    }

    suspend fun handleCallback(code: String, request: OAuthStateRegistry.ConsumedRequest): Boolean = withContext(Dispatchers.IO) {
        val config = configProvider() as? OAuthConfiguration.Configured ?: return@withContext false
        if (request.connectorId != id || code.isBlank() || request.codeVerifier.isNullOrBlank() || request.requestedScopes.isEmpty()) return@withContext false
        val body = FormBody.Builder()
            .add("code", code)
            .add("grant_type", "authorization_code")
            .add("client_id", config.clientId)
            .add("code_verifier", request.codeVerifier)
            .add("redirect_uri", config.redirectUri)
            .build()
        runCatching {
            http.newCall(Request.Builder().url(DropboxOAuthConfiguration.TOKEN_URL).post(body).build()).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val access = json.optString("access_token")
                if (!response.isSuccessful || access.isBlank()) return@use false
                val refresh = json.optString("refresh_token").ifBlank { null }
                val expiresIn = json.optLong("expires_in", 14_400L).coerceIn(60L, 86_400L)
                val granted = json.optString("scope").split(" ").map(String::trim).filter(String::isNotBlank).toSet()
                authManager.storeToken(id, access, refresh, System.currentTimeMillis() + expiresIn * 1000L, granted.ifEmpty { request.requestedScopes })
            }
        }.getOrDefault(false)
    }

    override suspend fun connect(): ConnectorState {
        val token = accessToken().getOrElse { return update(false, it.message ?: "Dropbox authorization is required") }
        val response = apiPost("/users/get_current_account", "{}", token)
        return if (response.code in 200..299) {
            val json = JSONObject(response.body)
            val name = json.optString("name").let { n ->
                if (n.isBlank()) json.optString("email") else n
            }
            update(true, "Connected to Dropbox${name.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty()}")
        } else {
            if (response.code == 401) authManager.revokeToken(id)
            update(false, "Dropbox health check failed (${response.code})")
        }
    }

    override suspend fun disconnect() {
        authManager.revokeToken(id)
        authManager.setExplicitlyDisconnected(id, true)
        _state.value = ConnectorState(false, false, "Disconnected")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        if (!_state.value.connected) return ConnectorOutput.Failure("not_connected", "Dropbox authorization is required", true)
        val required = actionScope(input.action)
        if (required != null && required !in authManager.getTokenScopes(id)) {
            return ConnectorOutput.Failure("permission_denied", "Dropbox scope '$required' was not granted by the user", false)
        }
        val token = accessToken().getOrElse {
            _state.value = ConnectorState(false, false, "Authorization expired", errorMessage = "Dropbox authorization expired")
            return ConnectorOutput.Failure("authorization_expired", "Dropbox authorization expired; reconnect is required", true)
        }
        val response = when (input.action) {
            "dropbox_account_read" -> apiPost("/users/get_current_account", "{}", token)
            "dropbox_files_list" -> {
                val path = input.params["path"]?.trim().orEmpty()
                val limit = input.params["limit"]?.toIntOrNull()?.coerceIn(1, 2000) ?: 100
                apiPost("/files/list_folder", JSONObject().put("path", path).put("recursive", false).put("limit", limit).toString(), token)
            }
            "dropbox_files_search" -> {
                val query = input.params["query"]?.trim().orEmpty()
                if (query.isBlank()) return ConnectorOutput.Failure("invalid_params", "query is required")
                val path = input.params["path"]?.trim()
                val body = JSONObject().put("query", query).put("options", JSONObject().put("max_results", 100).apply { if (!path.isNullOrBlank()) put("path", path) })
                apiPost("/files/search_v2", body.toString(), token)
            }
            "dropbox_file_metadata" -> {
                val path = input.params["path"]?.trim().orEmpty()
                if (path.isBlank()) return ConnectorOutput.Failure("invalid_params", "path is required")
                apiPost("/files/get_metadata", JSONObject().put("path", path).toString(), token)
            }
            "dropbox_file_temporary_link" -> {
                val path = input.params["path"]?.trim().orEmpty()
                if (path.isBlank()) return ConnectorOutput.Failure("invalid_params", "path is required")
                apiPost("/files/get_temporary_link", JSONObject().put("path", path).toString(), token)
            }
            "status" -> apiPost("/users/get_current_account", "{}", token)
            else -> return ConnectorOutput.Failure("unknown_action", "Unknown Dropbox action: ${input.action}")
        }
        if (response.code == 401) {
            authManager.revokeToken(id)
            _state.value = ConnectorState(false, false, "Authorization expired")
            return ConnectorOutput.Failure("authorization_expired", "Dropbox authorization expired; reconnect is required", true)
        }
        if (response.code !in 200..299) return providerFailure(response)
        return ConnectorOutput.Success(response.body, data = mapOf("provider" to "dropbox", "action" to input.action))
    }

    override fun agentActions() = listOf(
        ConnectorAgentAction("dropbox_account_read", "Read the signed-in user's Dropbox account", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ACCOUNT_READ))),
        ConnectorAgentAction("dropbox_files_list", "List file and folder metadata in an authorized Dropbox path", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, METADATA_READ)), parameters = mapOf(
            "path" to ConnectorAgentParameter(description = "Dropbox path; empty means the root folder.", maxLength = 1024),
            "limit" to ConnectorAgentParameter(description = "Maximum entries from 1 to 2000.", minInt = 1, maxInt = 2000),
        )),
        ConnectorAgentAction("dropbox_files_search", "Search authorized Dropbox file and folder metadata", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, METADATA_READ)), parameters = mapOf(
            "query" to ConnectorAgentParameter(description = "Search text.", required = true, maxLength = 256),
            "path" to ConnectorAgentParameter(description = "Optional Dropbox path restriction.", maxLength = 1024),
        )),
        ConnectorAgentAction("dropbox_file_metadata", "Read metadata for one authorized Dropbox path", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, METADATA_READ)), parameters = mapOf("path" to ConnectorAgentParameter(description = "Dropbox file or folder path.", required = true, maxLength = 1024))),
        ConnectorAgentAction("dropbox_file_temporary_link", "Create a short-lived link for an authorized Dropbox file", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, CONTENT_READ)), parameters = mapOf("path" to ConnectorAgentParameter(description = "Dropbox file path.", required = true, maxLength = 1024))),
        ConnectorAgentAction("status", "Check the Dropbox connection", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ACCOUNT_READ)),
        ),
    )

    private suspend fun accessToken(): Result<String> = withContext(Dispatchers.IO) {
        if (authManager.isTokenValid(id)) return@withContext Result.success(authManager.getToken(id)!!)
        val refresh = authManager.getRefreshToken(id) ?: return@withContext Result.failure(IllegalStateException("Dropbox authorization is required"))
        val config = configProvider() as? OAuthConfiguration.Configured ?: return@withContext Result.failure(IllegalStateException("Dropbox OAuth is not configured"))
        runCatching {
            val body = FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refresh).add("client_id", config.clientId).build()
            http.newCall(Request.Builder().url(DropboxOAuthConfiguration.TOKEN_URL).post(body).build()).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val access = json.optString("access_token")
                check(response.isSuccessful && access.isNotBlank()) { "Dropbox token refresh failed (${response.code})" }
                val expiresIn = json.optLong("expires_in", 14_400L).coerceIn(60L, 86_400L)
                check(authManager.storeToken(id, access, refresh, System.currentTimeMillis() + expiresIn * 1000L, authManager.getTokenScopes(id))) { "Secure storage unavailable" }
                access
            }
        }
    }

    private suspend fun apiPost(path: String, json: String, token: String): ApiResponse = withContext(Dispatchers.IO) {
        http.newCall(Request.Builder().url(DropboxOAuthConfiguration.API_BASE_URL + path).post(json.toRequestBody(JSON)).header("Authorization", "Bearer $token").header("Content-Type", "application/json").build()).execute().use { response ->
            ApiResponse(response.code, response.body?.string() ?: "{}")
        }
    }

    private fun actionScope(action: String): String? = when (action) {
        "dropbox_account_read", "status" -> ACCOUNT_READ
        "dropbox_files_list", "dropbox_files_search", "dropbox_file_metadata" -> METADATA_READ
        "dropbox_file_temporary_link" -> CONTENT_READ
        else -> null
    }

    private fun providerFailure(response: ApiResponse) = when {
        response.code == 403 -> ConnectorOutput.Failure("permission_denied", "Dropbox denied this capability", false)
        response.code == 429 -> ConnectorOutput.Failure("rate_limited", "Dropbox rate limit reached", true)
        response.code >= 500 -> ConnectorOutput.Failure("provider_unavailable", "Dropbox is temporarily unavailable (${response.code})", true)
        else -> ConnectorOutput.Failure("provider_error", "Dropbox request failed (${response.code})", false)
    }

    private fun update(connected: Boolean, message: String): ConnectorState = ConnectorState(connected, connected, message, System.currentTimeMillis()).also { _state.value = it }
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
