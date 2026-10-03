package com.airi.assistant.connector.app

import com.airi.assistant.connector.ConnectorAuthManager
import com.airi.assistant.connector.oauth.MicrosoftOAuthConfiguration
import com.airi.assistant.connector.oauth.OAuthConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/** Owns Microsoft access/refresh token rotation; callers never handle token strings. */
class MicrosoftGraphTokenService(
    private val authManager: ConnectorAuthManager,
    private val configProvider: () -> OAuthConfiguration = MicrosoftOAuthConfiguration::current,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    suspend fun accessToken(forceRefresh: Boolean = false): Result<String> = withContext(Dispatchers.IO) {
        if (!forceRefresh && authManager.isTokenValid(MicrosoftOAuthConfiguration.CONNECTOR_ID)) {
            return@withContext authManager.getToken(MicrosoftOAuthConfiguration.CONNECTOR_ID)
                ?.takeIf { it.isNotBlank() }
                ?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException("Microsoft access token is unavailable"))
        }
        val refreshToken = authManager.getRefreshToken(MicrosoftOAuthConfiguration.CONNECTOR_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return@withContext Result.failure(IllegalStateException("Microsoft authorization is required"))
        val config = configProvider() as? OAuthConfiguration.Configured
            ?: return@withContext Result.failure(IllegalStateException("Microsoft OAuth is not configured"))
        val body = FormBody.Builder()
            .add("client_id", config.clientId)
            .add("scope", "https://graph.microsoft.com/.default offline_access")
            .add("refresh_token", refreshToken)
            .add("grant_type", "refresh_token")
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
                if (!response.isSuccessful || access.isBlank()) {
                    if (json.optString("error") == "invalid_grant") {
                        authManager.revokeToken(MicrosoftOAuthConfiguration.CONNECTOR_ID)
                    }
                    error("Microsoft token refresh failed")
                }
                val rotatedRefresh = json.optString("refresh_token").ifBlank { refreshToken }
                val expiresIn = json.optLong("expires_in", 3600L).coerceIn(60L, 86_400L)
                check(authManager.storeToken(
                    MicrosoftOAuthConfiguration.CONNECTOR_ID,
                    access,
                    rotatedRefresh,
                    System.currentTimeMillis() + expiresIn * 1000L,
                )) { "Secure credential storage is unavailable" }
                access
            }
        }.fold(onSuccess = { Result.success(it) }, onFailure = { Result.failure(it) })
    }

    fun clear() {
        authManager.revokeToken(MicrosoftOAuthConfiguration.CONNECTOR_ID)
    }
}
