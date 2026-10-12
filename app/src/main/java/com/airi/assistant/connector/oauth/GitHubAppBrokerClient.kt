package com.airi.assistant.connector.oauth

import com.airi.assistant.BuildConfig
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Client for the OAuth Broker. The APK contains only this public endpoint;
 * GitHub client secrets and App private keys remain server-side.
 */
class GitHubAppBrokerClient(
    private val baseUrl: String = BuildConfig.GITHUB_BROKER_BASE_URL,
    private val http: OkHttpClient = OkHttpClient.Builder()
        .callTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    data class InstallStart(val authorizationUrl: String)
    data class Session(
        val token: String,
        val installationId: Long,
        val expiresAt: String,
        val repositories: List<String>,
        val permissions: Map<String, String>,
    )
    data class Execution(val ok: Boolean, val status: Int, val body: String)

    fun beginInstall(appState: String): InstallStart {
        val body = JSONObject()
            .put("state", appState)
            .put("redirect_uri", GitHubAppOAuthConfiguration.CALLBACK_URI)
            .toString()
            .toRequestBody(JSON)
        val json = post("/v1/github/install/start", body)
        return InstallStart(json.getString("authorization_url"))
    }

    fun completeInstall(ticket: String): Session {
        val json = post(
            "/v1/github/install/complete",
            JSONObject().put("ticket", ticket).toString().toRequestBody(JSON),
        )
        val permissions = buildMap {
            val source = json.optJSONObject("permissions") ?: JSONObject()
            source.keys().forEach { key -> put(key, source.optString(key)) }
        }
        val repositories = buildList {
            val source = json.optJSONArray("repositories") ?: return@buildList
            for (i in 0 until source.length()) add(source.optString(i))
        }
        return Session(
            token = json.getString("session_token"),
            installationId = json.getLong("installation_id"),
            expiresAt = json.getString("expires_at"),
            repositories = repositories,
            permissions = permissions,
        )
    }

    fun health(sessionToken: String): JSONObject = get(
        "/v1/github/session/health", sessionToken,
    )

    fun execute(sessionToken: String, action: String, params: Map<String, String>, text: String?): Execution {
        val json = JSONObject().put("action", action).put("params", JSONObject(params))
        if (text != null) json.put("text", text)
        val request = Request.Builder()
            .url(url("/v1/github/execute"))
            .header("Authorization", "Bearer $sessionToken")
            .header("Accept", "application/json")
            .post(json.toString().toRequestBody(JSON))
            .build()
        http.newCall(request).execute().use { response ->
            return Execution(response.isSuccessful, response.code, response.body?.string().orEmpty())
        }
    }

    private fun post(path: String, body: okhttp3.RequestBody): JSONObject {
        val request = Request.Builder().url(url(path)).header("Accept", "application/json").post(body).build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GitHub Broker request failed (${response.code})")
            return JSONObject(payload)
        }
    }

    private fun get(path: String, token: String): JSONObject {
        val request = Request.Builder().url(url(path)).header("Authorization", "Bearer $token").build()
        http.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) error("GitHub Broker health check failed (${response.code})")
            return JSONObject(payload)
        }
    }

    private fun url(path: String): String = baseUrl.trimEnd('/') + path
    private companion object { val JSON = "application/json; charset=utf-8".toMediaType() }
}
