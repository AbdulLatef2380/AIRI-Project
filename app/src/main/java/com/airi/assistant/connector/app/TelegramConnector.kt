package com.airi.assistant.connector.app

import android.util.Log
import com.airi.assistant.auth.SecureStorage
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
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * First-class Telegram Bot connector.
 *
 * Replaces [com.airi.assistant.connector.legacy.IntegrationConnectorAdapter] for Telegram.
 * The adapter only handled "status" queries; this connector exposes real bot API actions:
 *
 *   - send_message  : POST /sendMessage
 *   - get_updates   : GET  /getUpdates (last 10 messages)
 *   - get_chat_info : GET  /getChat
 *   - status        : returns current bot connection state
 *
 * Token storage: SecureStorage.saveTelegramToken / getTelegramToken
 * (uses the same slot as the existing Integrations flow — no migration needed)
 *
 * B-20 FIX: Was an IntegrationConnectorAdapter returning "Connected"/"Not connected"
 * for every execute() call. Now returns real Telegram API responses.
 */
class TelegramConnector(private val secureStorage: SecureStorage) : Connector {

    private val TAG = "TelegramConnector"
    private val API = "https://api.telegram.org/bot"

    override val id          = "telegram"
    override val name        = "Telegram"
    override val description = "Send messages and read updates via a Telegram Bot."
    override val type        = ConnectorType.APP

    private val _state = MutableStateFlow(
        ConnectorState(connected = false, statusLine = "Not connected")
    )
    override fun meta() = ConnectorMeta(
        id          = id,
        name        = name,
        description = description,
        type        = type,
        iconUrl     = null,
        tags        = listOf("telegram", "messaging", "bot", "chat")
    )
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun connect(): ConnectorState = withContext(Dispatchers.IO) {
        val token = secureStorage.getTelegramToken()
        if (token.isNullOrBlank()) {
            _state.value = ConnectorState(
                connected   = false,
                statusLine  = "No bot token",
                errorMessage = "Set a Telegram Bot token in Integrations settings"
            )
            return@withContext _state.value
        }
        return@withContext try {
            val me = get(token, "getMe")
            if (me.optBoolean("ok", false)) {
                val result   = me.optJSONObject("result")
                val username = result?.optString("username", "") ?: ""
                val display  = if (username.isNotBlank()) "@$username" else "Bot"
                _state.value = ConnectorState(
                    connected   = true,
                    statusLine  = "Connected as $display",
                    lastUpdatedMs = System.currentTimeMillis()
                )
                AgentActivityBus.emit("Telegram connected as $display", ActivityCategory.CONNECTOR)
                Log.i(TAG, "AIRI TELEGRAM_CONNECTED bot=$display")
            } else {
                val desc = me.optString("description", "Auth failed")
                _state.value = ConnectorState(false, statusLine = "Auth failed", errorMessage = desc)
            }
            _state.value
        } catch (e: Exception) {
            Log.w(TAG, "Telegram connect failed: ${e.message}")
            _state.value = ConnectorState(
                connected    = false,
                statusLine   = "Connection failed",
                errorMessage = e.message
            )
            _state.value
        }
    }

    override suspend fun disconnect() {
        _state.value = ConnectorState(connected = false, statusLine = "Disconnected")
    }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput =
        withContext(Dispatchers.IO) {
        val token = secureStorage.getTelegramToken()
            ?: return@withContext ConnectorOutput.Failure(
                "not_connected", "Telegram bot token not configured. Connect in Integrations."
            )
        val t0 = System.currentTimeMillis()
        return@withContext try {
            val result = when (input.action) {
                "send_message" -> {
                    val chatId = input.params["chat_id"]
                        ?: return@withContext ConnectorOutput.Failure("missing_param",
                            "send_message requires chat_id param. Example: chat_id=123456789")
                    val text = input.text.ifBlank {
                        input.params["text"]
                            ?: return@withContext ConnectorOutput.Failure("missing_param",
                                "send_message requires message text")
                    }
                    sendMessage(token, chatId, text)
                }
                "get_updates" -> {
                    val limit = input.params["limit"]?.toIntOrNull() ?: 10
                    getUpdates(token, limit)
                }
                "get_chat_info" -> {
                    val chatId = input.params["chat_id"]
                        ?: return@withContext ConnectorOutput.Failure("missing_param",
                            "get_chat_info requires chat_id param")
                    getChatInfo(token, chatId)
                }
                "status" -> _state.value.statusLine
                else -> return@withContext ConnectorOutput.Failure(
                    "unknown_action",
                    "Unknown action '${input.action}'. Available: send_message, get_updates, get_chat_info, status"
                )
            }
            AgentActivityBus.emit("Telegram: ${input.action}", ActivityCategory.CONNECTOR)
            ConnectorOutput.Success(result, durationMs = System.currentTimeMillis() - t0)
        } catch (e: Exception) {
            Log.e(TAG, "Telegram execute failed: ${e.message}")
            ConnectorOutput.Failure("api_error", e.message ?: "Telegram API error", retryable = true)
        }
    }

    // ── API helpers ──────────────────────────────────────────────────────────

    private fun sendMessage(token: String, chatId: String, text: String): String {
        val payload = JSONObject().apply {
            put("chat_id",    chatId)
            put("text",       text)
            put("parse_mode", "Markdown")
        }.toString()
        val body    = payload.toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("$API$token/sendMessage")
            .post(body)
            .header("Content-Type", "application/json")
            .build()
        val resp = http.newCall(request).execute()
        val json = JSONObject(resp.body?.string() ?: "{}")
        if (!json.optBoolean("ok", false)) {
            throw Exception(json.optString("description", "sendMessage failed"))
        }
        val msgId = json.optJSONObject("result")?.optInt("message_id") ?: 0
        return "Message sent to chat $chatId (message_id: $msgId)"
    }

    private fun getUpdates(token: String, limit: Int): String {
        val json = get(token, "getUpdates?limit=$limit&allowed_updates=[\"message\"]")
        if (!json.optBoolean("ok", false)) {
            throw Exception(json.optString("description", "getUpdates failed"))
        }
        val results = json.optJSONArray("result") ?: return "No updates found"
        if (results.length() == 0) return "No recent messages"
        return buildString {
            appendLine("Recent messages (${results.length()}):")
            for (i in 0 until results.length()) {
                val update  = results.getJSONObject(i)
                val message = update.optJSONObject("message") ?: continue
                val from    = message.optJSONObject("from")
                val name    = from?.optString("first_name", "Unknown") ?: "Unknown"
                val text    = message.optString("text", "[no text]")
                val chatId  = message.optJSONObject("chat")?.optLong("id")
                appendLine("• $name (chat: $chatId): ${text.take(100)}")
            }
        }.trim()
    }

    private fun getChatInfo(token: String, chatId: String): String {
        val json = get(token, "getChat?chat_id=$chatId")
        if (!json.optBoolean("ok", false)) {
            throw Exception(json.optString("description", "getChat failed"))
        }
        val result = json.optJSONObject("result") ?: return "No info"
        return buildString {
            appendLine("Chat info for $chatId:")
            appendLine("  Type: ${result.optString("type", "unknown")}")
            val title = result.optString("title", "")
            val uname = result.optString("username", "")
            if (title.isNotBlank()) appendLine("  Title: $title")
            if (uname.isNotBlank()) appendLine("  Username: @$uname")
            appendLine("  ID: ${result.optLong("id")}")
        }.trim()
    }

    private fun get(token: String, method: String): JSONObject {
        val url      = "$API$token/$method"
        val request  = Request.Builder().url(url).build()
        val response = http.newCall(request).execute()
        return JSONObject(response.body?.string() ?: "{}")
    }
}
