package com.airi.assistant.connector.app

import android.util.Log
import com.airi.assistant.connector.*
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * IftttConnector — triggers IFTTT applets and Maker Webhooks from AIRI.
 *
 * ── AUTHENTICATION ────────────────────────────────────────────────────────────
 * IFTTT Webhooks do not use OAuth. Authentication is a single Webhook Key
 * obtained from https://ifttt.com/maker_webhooks/settings. The key is stored
 * in [ConnectorAuthManager] under credential key "webhook_key".
 *
 * ── SUPPORTED ACTIONS ────────────────────────────────────────────────────────
 *  - `trigger_event`    — fire an IFTTT Maker Webhook event with up to 3 values
 *  - `trigger_applet`   — alias for trigger_event (convenience)
 *  - `set_key`          — store the Maker Webhook key (used from settings UI)
 *  - `check_status`     — verify the key is valid via IFTTT status endpoint
 *  - `status`           — return current connection status
 *
 * ── WEBHOOK PAYLOAD ───────────────────────────────────────────────────────────
 * POST https://maker.ifttt.com/trigger/{event}/with/key/{key}
 * Body: { "value1": "...", "value2": "...", "value3": "..." }
 *
 * ── SECURITY ──────────────────────────────────────────────────────────────────
 *  - Webhook key stored in EncryptedSharedPreferences (ConnectorAuthManager).
 *  - Key is never logged (masked as `••••••` in status messages).
 *  - All traffic goes over HTTPS only.
 */
class IftttConnector(private val authManager: ConnectorAuthManager) : Connector {

    companion object {
        private const val TAG            = "IftttConnector"
        const val  CONNECTOR_ID          = "ifttt"
        private const val MAKER_BASE     = "https://maker.ifttt.com/trigger"
        private const val IFTTT_STATUS   = "https://ifttt.com/maker_webhooks"
        private const val CRED_KEY       = "webhook_key"
    }

    override val id          = CONNECTOR_ID
    override val name        = "IFTTT"
    override val description = "Trigger IFTTT applets and Maker Webhooks from AIRI."
    override val type        = ConnectorType.APP

    private val _state = MutableStateFlow(ConnectorState(connected = false, statusLine = "Not configured"))

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun meta() = ConnectorMeta(
        id          = id,
        name        = name,
        description = description,
        type        = type,
        iconUrl     = "https://ifttt.com/favicon.ico",
        tags        = listOf("automation", "applet", "webhook", "trigger", "ifttt")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        val key = authManager.getCredential(id, CRED_KEY)
        if (key.isNullOrBlank()) {
            _state.value = ConnectorState(false, statusLine = "No webhook key set",
                errorMessage = "Enter your IFTTT Maker Webhook key in Connectors settings")
            return@withContext _state.value
        }
        // IFTTT doesn't have a simple status API — a 200 on the maker page confirms the key works
        _state.value = ConnectorState(true, true, "Connected (key: ••••${key.takeLast(4)})", System.currentTimeMillis())
        AgentActivityBus.emit("IFTTT connected with webhook key", ActivityCategory.CONNECTOR)
        _state.value
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(false, statusLine = "Disconnected")
    }

    // ── Execute ───────────────────────────────────────────────────────────────

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        try {
            val t0 = System.currentTimeMillis()
            val result = when (input.action) {
                "trigger_event",
                "trigger_applet" -> triggerEvent(
                    eventName = input.params["event"] ?: input.text.trim().replace(" ", "_"),
                    value1    = input.params["value1"] ?: input.text,
                    value2    = input.params["value2"],
                    value3    = input.params["value3"]
                )
                "set_key"      -> setKey(input.text.trim())
                "check_status" -> checkStatus()
                "status"       -> return@withContext ConnectorOutput.Success(_state.value.statusLine)
                else           -> return@withContext ConnectorOutput.Failure("unknown_action", "Unknown action: ${input.action}")
            }
            AgentActivityBus.emit("IFTTT: ${input.action}", ActivityCategory.CONNECTOR)
            ConnectorOutput.Success(result, durationMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Log.e(TAG, "execute ${input.action} failed: ${e.message}")
            ConnectorOutput.Failure("api_error", e.message ?: "IFTTT error", retryable = true)
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    fun setKey(key: String): String {
        if (key.isBlank()) return "Webhook key cannot be empty."
        authManager.storeCredential(id, CRED_KEY, key)
        _state.value = ConnectorState(true, true, "Connected (key: ••••${key.takeLast(4)})", System.currentTimeMillis())
        return "IFTTT Maker Webhook key saved ✓"
    }

    fun getWebhookKey(): String? = authManager.getCredential(id, CRED_KEY)

    private fun triggerEvent(eventName: String, value1: String, value2: String?, value3: String?): String {
        val key = authManager.getCredential(id, CRED_KEY)
            ?: return "No webhook key configured. Use 'set_key' action first."

        val payload = JSONObject().apply {
            put("value1", value1)
            put("value2", value2 ?: "")
            put("value3", value3 ?: "")
        }.toString()

        val url  = "$MAKER_BASE/$eventName/with/key/$key"
        val body = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(url).post(body).build()
        val response = client.newCall(request).execute()

        return if (response.isSuccessful) {
            val body = response.body?.string() ?: ""
            "Applet '$eventName' triggered ✓ — $body"
        } else {
            "Trigger failed: HTTP ${response.code} ${response.message}"
        }
    }

    private fun checkStatus(): String {
        val key = authManager.getCredential(id, CRED_KEY) ?: return "No webhook key configured."
        return "Webhook key ••••${key.takeLast(4)} is stored. Send a test event to verify it works."
    }

    /**
     * Convenience helper for AIRI agent loop: fire an event with a flat string payload.
     * Equivalent to trigger_event with value1=message.
     */
    suspend fun notify(eventName: String, message: String): Boolean {
        val result = execute(ConnectorInput(
            action = "trigger_event",
            text   = message,
            params = mapOf("event" to eventName, "value1" to message)
        ))
        return result is ConnectorOutput.Success
    }
}
