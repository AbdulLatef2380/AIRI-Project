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
import java.util.concurrent.TimeUnit

/**
 * OpenAI Chat Completions provider.
 *
 * Talks to `https://api.openai.com/v1/chat/completions` (or any
 * OpenAI-compatible endpoint — the base URL is overridable so this same
 * provider works against Together, Groq, OpenRouter, vLLM, llama.cpp's
 * server, etc.).
 *
 * Configuration:
 *   - `apiKey`  required. Resolved at construction time, NOT cached
 *     statically — pass a `keyProvider: () -> String?` so the connector
 *     can be re-keyed without restarting the registry.
 *   - `model`   defaults to `gpt-4o-mini` (cheap + fast).
 *   - `baseUrl` defaults to `https://api.openai.com/v1`.
 *
 * Per-request overrides via [RemoteLlmConnector.complete] params:
 *   - `model`        switch model for this call only
 *   - `temperature`  parsed as Double, default 0.7
 *   - `max_tokens`   parsed as Int,    default 1024
 *
 * Threading: [complete] is `suspend` and runs the blocking okhttp call
 * on `Dispatchers.IO`. The HTTP client is constructed once and reused.
 *
 * Errors: thrown as [IOException] so [RemoteLlmConnector] wraps them as
 * a retryable `provider_error` and falls through to the next provider.
 */
class OpenAiProvider(
    private val keyProvider: () -> String?,
    private val defaultModel: String = "gpt-4o-mini",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val httpClient: OkHttpClient = defaultHttpClient(),
) : RemoteLlmConnector.Provider {

    override val label: String = "openai"

    override fun isConfigured(): Boolean = !keyProvider().isNullOrBlank()

    override suspend fun complete(prompt: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val key = keyProvider()
                ?: throw IOException("OpenAI key missing")

            val body = JsonObject().apply {
                addProperty("model", params["model"] ?: defaultModel)
                addProperty("temperature", params["temperature"]?.toDoubleOrNull() ?: 0.7)
                addProperty("max_tokens",  params["max_tokens"]?.toIntOrNull()    ?: 1024)
                add("messages", Gson().toJsonTree(listOf(
                    mapOf("role" to "user", "content" to prompt),
                )))
            }.toString()

            val req = Request.Builder()
                .url("${baseUrl.trimEnd('/')}/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("openai http ${resp.code}: ${text.take(400)}")
                }
                extractContent(text)
            }
        }

    private fun extractContent(json: String): String {
        // {"choices":[{"message":{"content":"..."}}]}
        val root = JsonParser.parseString(json).asJsonObject
        val choices = root.getAsJsonArray("choices")
            ?: throw IOException("openai: no 'choices' in response")
        if (choices.size() == 0) throw IOException("openai: empty choices")
        val msg = choices[0].asJsonObject.getAsJsonObject("message")
            ?: throw IOException("openai: no 'message' in choice")
        return msg.get("content")?.asString
            ?: throw IOException("openai: no 'content' in message")
    }

    companion object {
        private val JSON = "application/json".toMediaType()

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }
}
