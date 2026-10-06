package com.airi.assistant.connector.app

import android.util.Log
import com.airi.assistant.connector.*
import com.airi.assistant.connector.oauth.OAuthStateRegistry
import com.airi.assistant.domain.release.ReleaseScopePolicy
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * ZapierConnector — read-only Powered by Zapier / Workflow API contract.
 * The OAuth exchange is intentionally not implemented in Android: Zapier's
 * official user-token flow is confidential and requires a server-held secret.
 *
 * ── AUTHENTICATION FLOW ──────────────────────────────────────────────────────
 * Official mismatch analysis (verified against Zapier docs on 2026-10-06):
 *   Product/Flow: Powered by Zapier / Workflow API (not REST Hooks)
 *   Authorize: https://api.zapier.com/v2/authorize
 *   Token: https://zapier.com/oauth/token/
 *   Client: confidential; client secret must stay server-side
 *   PKCE: not the documented user-access-token exchange for this flow
 *   Scope: `zap` for integration-owned Zaps
 *   Read endpoint: GET https://api.zapier.com/v2/zaps
 *   Refresh: server-side refresh-token rotation
 *   Revoke: provider support must be confirmed before claiming it
 *   `code_verifier`: deliberately not accepted or stored; this is not a local PKCE exchange
 *
 * ── SUPPORTED ACTIONS ────────────────────────────────────────────────────────
 *  - `list_zaps`         — list Zaps visible to the authorized integration
 *  - Only `list_zaps` and `status` are executable; trigger/webhook actions are intentionally absent.
 *  - `status`            — return the current connection status string
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────────
 *  - Tokens stored in EncryptedSharedPreferences via [ConnectorAuthManager].
 *  - OAuth state is 144-bit SecureRandom (OAuthStateRegistry).
 *  - Provider API calls use fixed HTTPS endpoints; cleartext traffic is disabled
 *    by the app network policy. User-entered webhook URLs are host-allowlisted
 *    and redirects are disabled.
 */
class ZapierConnector(private val authManager: ConnectorAuthManager) : Connector {

    companion object {
        private const val TAG           = "ZapierConnector"
        const val  CONNECTOR_ID         = "zapier"
        const val PRODUCT_FLOW = "Powered by Zapier / Workflow API"
        const val AUTHORIZE_URL = "https://api.zapier.com/v2/authorize"
        const val TOKEN_URL = "https://zapier.com/oauth/token/"
        const val API_BASE_URL = "https://api.zapier.com/v2"
        const val CLIENT_TYPE = "CONFIDENTIAL_SERVER_SIDE"
        const val REQUIRED_SCOPE = ConnectorProviderScopes.ZAPIER_ZAP_READ
    }

    override val id          = CONNECTOR_ID
    override val name        = "Zapier"
    override val description = "Connect AIRI to 6000+ apps via Zapier automations."
    override val type        = ConnectorType.APP

    private val _state = MutableStateFlow(ConnectorState(connected = false, statusLine = "Not connected"))

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    override fun meta() = ConnectorMeta(
        id          = id,
        name        = name,
        description = description,
        type        = type,
        iconUrl     = "https://cdn.zapier.com/zapier/images/logos/zapier-logomark.png",
        tags        = listOf("automation", "workflow", "webhook", "zaps", "no-code")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override fun agentActions() = listOf(
        ConnectorAgentAction(
            "list_zaps",
            "List Zaps visible to the AIRI Zapier integration.",
            providerGrants = listOf(ConnectorProviderGrant(ConnectorProviderGrantKind.OAUTH_SCOPE, ConnectorProviderScopes.ZAPIER_ZAP_READ)),
        ),
        ConnectorAgentAction("status", "Return the current Zapier connection status."),
    )

    fun isOAuthConfigured(): Boolean = false

    // ── Auth URL ──────────────────────────────────────────────────────────────

    /**
     * Build the Zapier OAuth 2.0 authorization URL.
     * Call this before opening the browser — the returned state token is
     * stored in [OAuthStateRegistry] and will be validated in [handleCallback].
     */
    fun buildAuthUrl(requestedScopes: Set<String>): String {
        if (!ReleaseScopePolicy.externalAutomationIntegrationsEnabled) {
            error("External automation integrations are unavailable in this release")
        }
        require(requestedScopes == setOf(REQUIRED_SCOPE)) {
            "Zapier authorization accepts only the declared zap scope for the read-only action"
        }
        error("Zapier requires a server-side OAuth broker; client credentials must never be shipped in Android")
    }

    /**
     * Process the OAuth callback deep link.
     * [uri] is the full `airi://oauth/callback?code=...&state=...` URI.
     *
     * Returns `true` if the exchange succeeded and tokens are stored.
     */
    suspend fun handleCallback(uri: android.net.Uri): Boolean = withContext(Dispatchers.IO) {
        val state = uri.getQueryParameter("state") ?: run {
            Log.w(TAG, "OAuth callback missing state")
            return@withContext false
        }
        val code  = uri.getQueryParameter("code") ?: run {
            Log.w(TAG, "OAuth callback missing code")
            return@withContext false
        }

        val request = OAuthStateRegistry.consumeRequest(state)
            ?: return@withContext false
        return@withContext handleCallback(code, request)
    }

    suspend fun handleCallback(
        code: String,
        requestContext: OAuthStateRegistry.ConsumedRequest
    ): Boolean = withContext(Dispatchers.IO) {
        Log.w(TAG, "Rejected Zapier callback: confidential token exchange belongs to the server-side broker")
        false
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        if (!ReleaseScopePolicy.externalAutomationIntegrationsEnabled) {
            _state.value = ConnectorState(
                connected = false,
                statusLine = "Unavailable in this release",
                errorMessage = "External automation integration is not enabled."
            )
            return@withContext _state.value
        }
        if (!authManager.isTokenValid(id)) {
            _state.value = ConnectorState(false, statusLine = "Not authenticated", errorMessage = "Complete OAuth to connect")
            return@withContext _state.value
        }
        try {
            val user = apiGet("/user")
            val email = user.optJSONObject("user")?.optString("email") ?: "unknown"
            _state.value = ConnectorState(true, true, "Connected as $email", System.currentTimeMillis())
            AgentActivityBus.emit("Zapier connected as $email", ActivityCategory.CONNECTOR)
        } catch (e: Exception) {
            _state.value = ConnectorState(false, statusLine = "Connection failed: ${e.message}", errorMessage = e.message)
        }
        _state.value
    }

    override suspend fun disconnect() {
        authManager.revokeToken(id)
        _state.value = ConnectorState(false, statusLine = "Disconnected")
    }

    // ── Execute ───────────────────────────────────────────────────────────────

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        if (!ReleaseScopePolicy.externalAutomationIntegrationsEnabled) {
            return@withContext ConnectorOutput.Failure(
                "integration_unavailable",
                "External automation integrations are unavailable in this release."
            )
        }
        if (input.action == "list_zaps" && !authManager.isTokenValid(id)) {
            return@withContext ConnectorOutput.Failure("not_connected", "Zapier not authenticated. Complete OAuth first.")
        }
        try {
            val t0 = System.currentTimeMillis()
            val result = when (input.action) {
                "list_zaps"    -> listZaps()
                "trigger_zap" -> return@withContext ConnectorOutput.Failure(
                    "unsupported_action",
                    "Zapier agent-trigger execution is disabled until a trusted REST Hook URL is securely configured.",
                )
                "pause_zap", "resume_zap" -> return@withContext ConnectorOutput.Failure(
                    "unsupported_action",
                    "Zapier pause/resume is not implemented by this adapter and was not executed.",
                )
                "list_triggers" -> return@withContext ConnectorOutput.Failure(
                    "unsupported_action", "Zapier trigger discovery is not implemented by this adapter."
                )
                "status"       -> return@withContext ConnectorOutput.Success(_state.value.statusLine)
                else           -> return@withContext ConnectorOutput.Failure("unknown_action", "Unknown action: ${input.action}")
            }
            AgentActivityBus.emit("Zapier: ${input.action}", ActivityCategory.CONNECTOR)
            ConnectorOutput.Success(result, durationMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Log.e(TAG, "ZAPIER_EXECUTION_FAILURE action=${input.action} causeType=${e::class.simpleName}")
            ConnectorOutput.Failure("api_error", e.message ?: "Zapier API error", retryable = true)
        }
    }

    // ── API helpers ───────────────────────────────────────────────────────────

    private fun listZaps(): String {
        val json = apiGet("/zaps")
        val zaps = json.optJSONArray("objects") ?: return "No Zaps found."
        return buildString {
            appendLine("Your Zaps (${zaps.length()}):")
            for (i in 0 until zaps.length()) {
                val z = zaps.getJSONObject(i)
                val status = if (z.optBoolean("active", false)) "" else "⏸"
                appendLine("$status ${z.optString("title","Untitled")} [id: ${z.optInt("id")}]")
            }
        }
    }

    private fun apiGet(path: String): JSONObject {
        val token = authManager.getToken(id) ?: throw IllegalStateException("No access token")
        val request = Request.Builder()
            .url("$API_BASE_URL$path")
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/json")
            .build()
        return client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("Zapier API returned HTTP ${response.code}")
            }
            JSONObject(responseBody.ifBlank { "{}" })
        }
    }
}
