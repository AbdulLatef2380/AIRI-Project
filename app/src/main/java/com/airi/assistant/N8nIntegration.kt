package com.airi.assistant

import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/**
 * AIRI Integration with n8n Automation Engine
 * This module handles sending JSON requests to n8n Webhooks.
 */
class N8nIntegration(private val webhookUrl: String = "http://localhost:5678/webhook/airi") {

    private val client = OkHttpClient()
    private val gson = Gson()

    data class N8nRequest(
        val source: String = "AIRI",
        val intent: String,
        val user: UserInfo,
        val command: CommandInfo,
        val metadata: MetadataInfo
    )

    data class UserInfo(
        val id: String,
        val language: String,
        val device: String = "android"
    )

    data class CommandInfo(
        val action: String,
        val title: String,
        val priority: String,
        val context: String
    )

    data class MetadataInfo(
        val timestamp: String,
        val session_id: String,
        val policy_verified: Boolean = true
    )

    /**
     * Sends an automation request to n8n
     */
    suspend fun sendAutomationRequest(
        intent: String,
        action: String,
        title: String,
        priority: String = "medium",
        context: String = "general",
        userId: String = "user_001",
        language: String = "ar",
        sessionId: String = "airi-session-${System.currentTimeMillis()}"
    ): String? = withContext(Dispatchers.IO) {
        
        val requestData = N8nRequest(
            intent = intent,
            user = UserInfo(id = userId, language = language),
            command = CommandInfo(action = action, title = title, priority = priority, context = context),
            metadata = MetadataInfo(
                timestamp = java.time.OffsetDateTime.now().toString(),
                session_id = sessionId
            )
        )

        val json = gson.toJson(requestData)
        val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
        
        val request = Request.Builder()
            .url(webhookUrl)
            .post(body)
            .build()

        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) throw IOException("Unexpected code $response")
                return@withContext response.body?.string()
            }
        } catch (e: Exception) {
            e.printStackTrace()
            return@withContext null
        }
    }
}
