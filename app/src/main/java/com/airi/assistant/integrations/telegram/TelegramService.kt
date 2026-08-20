package com.airi.assistant.integrations.telegram

import com.airi.assistant.ai.tools.ToolResult
import com.airi.assistant.auth.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class TelegramService(private val secureStorage: SecureStorage) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    // ─── : Validate & connect ──────────────────────────────────────────

    suspend fun validateAndConnect(token: String): Result<String> = withContext(Dispatchers.IO) {
        if (token.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Token cannot be empty"))
        }
        try {
            val cleanToken = token.trim()
            val response = get("https://api.telegram.org/bot$cleanToken/getMe")
            val body = response.body?.string() ?: ""
            val json = JSONObject(body)

            if (json.optBoolean("ok", false)) {
                val result = json.optJSONObject("result")
                val username = result?.optString("username", "") ?: ""
                val display = if (username.isNotBlank()) "@$username" else result?.optString("first_name", "") ?: ""
                secureStorage.saveTelegramToken(cleanToken)
                secureStorage.saveTelegramConnected(true, display)
                Result.success(display)
            } else {
                val description = json.optString("description", "Invalid token")
                if (description.contains("Unauthorized", ignoreCase = true)) {
                    secureStorage.saveTelegramConnected(false)
                }
                Result.failure(Exception(description))
            }
        } catch (e: Exception) {
            Result.failure(Exception("Connection failed: ${e.message}"))
        }
    }

    // ─── : API calls ────────────────────────────────────────────────────

    suspend fun sendMessage(chatId: String, text: String): ToolResult = withContext(Dispatchers.IO) {
        val token = secureStorage.getTelegramToken()
            ?: return@withContext ToolResult(false, "", "Telegram token not found. Please reconnect.")
        if (chatId.isBlank()) return@withContext ToolResult(false, "", "chat_id is required")
        if (text.isBlank()) return@withContext ToolResult(false, "", "Message text is required")
        try {
            val payload = JSONObject().apply {
                put("chat_id", chatId)
                put("text", text)
                put("parse_mode", "Markdown")
            }.toString()

            val body = payload.toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendMessage")
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""
            val json = JSONObject(responseBody)

            if (json.optBoolean("ok", false)) {
                val msgId = json.optJSONObject("result")?.optInt("message_id") ?: 0
                ToolResult(true, "Message sent successfully to $chatId (message_id: $msgId)")
            } else {
                val description = json.optString("description", "Send failed")
                if (description.contains("Unauthorized", ignoreCase = true)) {
                    secureStorage.saveTelegramConnected(false)
                    ToolResult(false, "", "Telegram token expired. Please reconnect in Integrations.")
                } else {
                    ToolResult(false, "", description)
                }
            }
        } catch (e: Exception) {
            ToolResult(false, "", "Request failed: ${e.message}")
        }
    }

    fun disconnect() = secureStorage.disconnect("telegram")

    // ─── Internal ─────────────────────────────────────────────────────────────

    private fun get(url: String) = client.newCall(
        Request.Builder().url(url).build()
    ).execute()
}
