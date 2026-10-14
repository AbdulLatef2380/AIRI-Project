package com.airi.assistant.connector.app

import com.airi.assistant.connector.*
import com.airi.assistant.connector.oauth.BoxOAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Box user-linked connector.
 *
 * Box's documented OAuth exchange requires a client secret. Android therefore
 * never exchanges the authorization code directly: the code and one-use AIRI
 * state are sent to a configured HTTPS broker that owns the Box secret. The
 * access and refresh tokens returned by that broker are encrypted locally.
 * This adapter intentionally exposes read-only actions under root_readonly.
 */
class BoxConnector(
    private val authManager: ConnectorAuthManager,
    private val configProvider: () -> OAuthConfiguration = BoxOAuthConfiguration::current,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) : Connector {
    companion object {
        const val CONNECTOR_ID = "box"
        const val ROOT_READONLY = ConnectorProviderScopes.BOX_ROOT_READONLY
        private const val ROOT_FOLDER_ID = "0"
    }

    private data class ApiResponse(val code: Int, val body: String)
    private val _state = MutableStateFlow(ConnectorState(false, false, "Not connected"))

    override val id: String = CONNECTOR_ID
    override val name: String = "Box"
    override val description: String = "Read the signed-in user's authorized Box files and folders."
    override val type: ConnectorType = ConnectorType.APP

    override fun meta() = ConnectorMeta(
        id = id,
        name = name,
        description = description,
        type = type,
        provider = "Box",
        category = "Files",
        authenticationType = ConnectorAuthenticationType.OAUTH2,
        capabilities = listOf(
            ConnectorCapability("account.read", "Read the authorized Box user"),
            ConnectorCapability("files.metadata.read", "List and inspect authorized Box file metadata"),
            ConnectorCapability("files.search.read", "Search authorized Box content"),
        ),
        availability = ConnectorAvailability.PARTIAL,
        website = "https://www.box.com",
        documentationUrl = "https://developer.box.com/guides/authentication/oauth2",
        tags = listOf("box", "files", "storage"),
    )

    override fun state() = _state.asStateFlow()
    fun oauthConfiguration(): OAuthConfiguration = configProvider()

    fun buildAuthUrl(): Result<String> {
        val config = configProvider() as? OAuthConfiguration.Configured
            ?: return Result.failure(IllegalStateException("Box OAuth broker is not configured"))
        val state = OAuthStateRegistry.issueWithScopes(id, setOf(ROOT_READONLY))
        val query = linkedMapOf(
            "client_id" to config.clientId,
            "response_type" to "code",
            "redirect_uri" to config.redirectUri,
            "state" to state,
            "scope" to ROOT_READONLY,
        )
        return Result.success(
            BoxOAuthConfiguration.AUTHORIZE_URL + "?" +
                query.entries.joinToString("&") { (key, value) -> "${enc(key)}=${enc(value)}" }
        )
    }

    suspend fun handleCallback(code: String, request: OAuthStateRegistry.ConsumedRequest): Boolean = withContext(Dispatchers.IO) {
        val config = configProvider() as? OAuthConfiguration.Configured ?: return@withContext false
        if (request.connectorId != id || code.isBlank() || request.requestedScopes != setOf(ROOT_READONLY)) {
            return@withContext false
        }
        val brokerUrl = config.tenant.trimEnd('/') + BoxOAuthConfiguration.TOKEN_BROKER_PATH
        val body = FormBody.Builder()
            .add("provider", CONNECTOR_ID)
            .add("code", code)
            .add("redirect_uri", config.redirectUri)
            .add("requested_scope", ROOT_READONLY)
            .build()
        runCatching {
            http.newCall(Request.Builder().url(brokerUrl).post(body).build()).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val access = json.optString("access_token")
                if (!response.isSuccessful || access.isBlank()) return@use false
                val refresh = json.optString("refresh_token").ifBlank { null }
                val expiresIn = json.optLong("expires_in", 3_600L).coerceIn(60L, 86_400L)
                val granted = json.optString("scope").split(" ").map(String::trim).filter(String::isNotBlank).toSet()
                val effective = granted.ifEmpty { request.requestedScopes }
                if (!effective.contains(ROOT_READONLY)) return@use false
                authManager.storeToken(id, access, refresh, System.currentTimeMillis() + expiresIn * 1000L, effective)
            }
        }.getOrDefault(false)
    }

    override suspend fun connect(): ConnectorState {
        val token = accessToken().getOrElse { return update(false, it.message ?: "Box authorization is required") }
        val response = apiGet("/users/me", token)
        return if (response.code in 200..299) {
            val login = JSONObject(response.body).optString("login")
            update(true, "Connected to Box${login.takeIf { it.isNotBlank() }?.let { " as $it" }.orEmpty()}")
        } else {
            if (response.code == 401) authManager.revokeToken(id)
            update(false, "Box health check failed (${response.code})")
        }
    }

    override suspend fun disconnect() {
        authManager.revokeToken(id)
        authManager.setExplicitlyDisconnected(id, true)
        _state.value = ConnectorState(false, false, "Disconnected")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (!_state.value.connected) return@withContext ConnectorOutput.Failure("not_connected", "Box authorization is required", true)
        if (ROOT_READONLY !in authManager.getTokenScopes(id)) {
            return@withContext ConnectorOutput.Failure("permission_denied", "Box read scope was not granted by the user", false)
        }
        val token = accessToken().getOrElse {
            _state.value = ConnectorState(false, false, "Authorization expired", errorMessage = "Box authorization expired")
            return@withContext ConnectorOutput.Failure("authorization_expired", "Box authorization expired; reconnect is required", true)
        }
        val response = when (input.action) {
            "box_account_read", "status" -> apiGet("/users/me", token)
            "box_files_list" -> {
                val folderId = input.params["folder_id"]?.trim().orEmpty().ifBlank { ROOT_FOLDER_ID }
                if (!validId(folderId)) return@withContext ConnectorOutput.Failure("invalid_params", "folder_id must be a numeric Box folder id")
                val limit = input.params["limit"]?.toIntOrNull()?.coerceIn(1, 1_000) ?: 100
                apiGet("/folders/$folderId/items?limit=$limit&fields=id,type,name,size,modified_at,parent", token)
            }
            "box_files_search" -> {
                val query = input.params["query"]?.trim().orEmpty()
                if (query.isBlank()) return@withContext ConnectorOutput.Failure("invalid_params", "query is required")
                val url = (BoxOAuthConfiguration.API_BASE_URL + "/search").toHttpUrl().newBuilder()
                    .addQueryParameter("query", query)
                    .addQueryParameter("limit", (input.params["limit"]?.toIntOrNull()?.coerceIn(1, 200) ?: 50).toString())
                    .addQueryParameter("fields", "id,type,name,size,modified_at,parent")
                    .build()
                apiGet(url.toString(), token)
            }
            "box_file_metadata" -> {
                val fileId = input.params["file_id"]?.trim().orEmpty()
                if (!validId(fileId)) return@withContext ConnectorOutput.Failure("invalid_params", "file_id must be a numeric Box file id")
                apiGet("/files/$fileId?fields=id,type,name,size,modified_at,parent,description", token)
            }
            else -> return@withContext ConnectorOutput.Failure("unknown_action", "Unknown Box action: ${input.action}")
        }
        if (response.code == 401) {
            authManager.revokeToken(id)
            _state.value = ConnectorState(false, false, "Authorization expired")
            return@withContext ConnectorOutput.Failure("authorization_expired", "Box authorization expired; reconnect is required", true)
        }
        if (response.code !in 200..299) return@withContext providerFailure(response)
        ConnectorOutput.Success(response.body, data = mapOf("provider" to "box", "action" to input.action))
    }

    override fun agentActions() = listOf(
        ConnectorAgentAction("box_account_read", "Read the signed-in user's Box profile", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ROOT_READONLY))),
        ConnectorAgentAction("box_files_list", "List metadata for items in an authorized Box folder", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ROOT_READONLY)), parameters = mapOf(
            "folder_id" to ConnectorAgentParameter(description = "Numeric Box folder id; empty means root folder 0.", maxLength = 64),
            "limit" to ConnectorAgentParameter(type = "int", description = "Maximum items from 1 to 1000.", minInt = 1, maxInt = 1000),
        )),
        ConnectorAgentAction("box_files_search", "Search authorized Box files and folders", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ROOT_READONLY)), parameters = mapOf(
            "query" to ConnectorAgentParameter(description = "Search text.", required = true, maxLength = 256),
            "limit" to ConnectorAgentParameter(type = "int", description = "Maximum results from 1 to 200.", minInt = 1, maxInt = 200),
        )),
        ConnectorAgentAction("box_file_metadata", "Read metadata for one authorized Box file", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ROOT_READONLY)), parameters = mapOf(
            "file_id" to ConnectorAgentParameter(description = "Numeric Box file id.", required = true, maxLength = 64),
        )),
        ConnectorAgentAction("status", "Check the Box connection", surfaceId = id, providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ROOT_READONLY)),
        ),
    )

    private suspend fun accessToken(): Result<String> = withContext(Dispatchers.IO) {
        if (authManager.isTokenValid(id)) return@withContext Result.success(authManager.getToken(id)!!)
        val refresh = authManager.getRefreshToken(id) ?: return@withContext Result.failure(IllegalStateException("Box authorization is required"))
        val config = configProvider() as? OAuthConfiguration.Configured ?: return@withContext Result.failure(IllegalStateException("Box OAuth broker is not configured"))
        val url = config.tenant.trimEnd('/') + "/v1/connectors/box/oauth/refresh"
        runCatching {
            val body = FormBody.Builder().add("provider", CONNECTOR_ID).add("refresh_token", refresh).build()
            http.newCall(Request.Builder().url(url).post(body).build()).execute().use { response ->
                val json = JSONObject(response.body?.string() ?: "{}")
                val access = json.optString("access_token")
                check(response.isSuccessful && access.isNotBlank()) { "Box token refresh failed (${response.code})" }
                val newRefresh = json.optString("refresh_token").ifBlank { refresh }
                val expiresIn = json.optLong("expires_in", 3_600L).coerceIn(60L, 86_400L)
                check(authManager.storeToken(id, access, newRefresh, System.currentTimeMillis() + expiresIn * 1000L, authManager.getTokenScopes(id))) { "Secure storage unavailable" }
                access
            }
        }
    }

    private suspend fun apiGet(pathOrUrl: String, token: String): ApiResponse = withContext(Dispatchers.IO) {
        val url = if (pathOrUrl.startsWith("https://")) pathOrUrl else BoxOAuthConfiguration.API_BASE_URL + pathOrUrl
        http.newCall(Request.Builder().url(url).get().header("Authorization", "Bearer $token").build()).execute().use { response ->
            ApiResponse(response.code, response.body?.string() ?: "{}")
        }
    }

    private fun providerFailure(response: ApiResponse) = when {
        response.code == 403 -> ConnectorOutput.Failure("permission_denied", "Box denied this capability", false)
        response.code == 429 -> ConnectorOutput.Failure("rate_limited", "Box rate limit reached", true)
        response.code >= 500 -> ConnectorOutput.Failure("provider_unavailable", "Box is temporarily unavailable (${response.code})", true)
        else -> ConnectorOutput.Failure("provider_error", "Box request failed (${response.code})", false)
    }

    private fun update(connected: Boolean, message: String) = ConnectorState(connected, connected, message, System.currentTimeMillis()).also { _state.value = it }
    private fun validId(value: String) = value.matches(Regex("[0-9]+"))
    private fun enc(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
