package com.airi.assistant.connector.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Anthropic Messages API provider.
 *
 * Endpoint: `https://api.anthropic.com/v1/messages`
 * Auth:     `x-api-key: <key>` header (NOT bearer)
 * Version:  pinned via `anthropic-version: 2023-06-01` (current stable)
 *
 * Configuration:
 *   - `apiKey`  required
 *   - `model`   defaults to `claude-3-5-haiku-latest` (cheapest tier)
 *
 * Per-request param overrides:
 *   - `model`        switch model
 *   - `temperature`  Double, default 0.7
 *   - `max_tokens`   Int,    default 1024 (REQUIRED by Anthropic API)
 *
 * Response shape:
 *   { "content": [ { "type": "text", "text": "..." }, ... ] }
 *
 * The provider concatenates ALL text-typed content blocks; non-text
 * blocks (e.g. tool_use) are ignored at this layer. Tool calling lives
 * in a separate connector when implemented.
 */
class AnthropicProvider(
    private val keyProvider: () -> String?,
    private val defaultModel: String = "claude-3-5-haiku-latest",
    private val baseUrl: String = "https://api.anthropic.com/v1",
    private val httpClient: OkHttpClient = OpenAiProvider.defaultHttpClient(),
) : RemoteLlmConnector.Provider {

    override val label: String = "anthropic"

    override fun isConfigured(): Boolean = !keyProvider().isNullOrBlank()

    override suspend fun complete(prompt: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val key = keyProvider()
                ?: throw IOException("Anthropic key missing")

            val body = JsonObject().apply {
                addProperty("model", params["model"] ?: defaultModel)
                addProperty("max_tokens", params["max_tokens"]?.toIntOrNull() ?: 1024)
                addProperty("temperature", params["temperature"]?.toDoubleOrNull() ?: 0.7)
                add("messages", Gson().toJsonTree(listOf(
                    mapOf("role" to "user", "content" to prompt),
                )))
            }.toString()

            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/messages")
                .header("x-api-key", key)
                .header("anthropic-version", ANTHROPIC_API_VERSION)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("anthropic http ${resp.code}: ${text.take(400)}")
                }
                extractContent(text)
            }
        }

    private fun extractContent(json: String): String {
        val root = JsonParser.parseString(json).asJsonObject
        val arr = root.getAsJsonArray("content")
            ?: throw IOException("anthropic: no 'content' array")
        val sb = StringBuilder()
        for (i in 0 until arr.size()) {
            val obj = arr[i].asJsonObject
            if (obj.get("type")?.asString == "text") {
                sb.append(obj.get("text")?.asString.orEmpty())
            }
        }
        if (sb.isEmpty()) throw IOException("anthropic: empty text content")
        return sb.toString()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
        private const val ANTHROPIC_API_VERSION = "2023-06-01"
    }
}
