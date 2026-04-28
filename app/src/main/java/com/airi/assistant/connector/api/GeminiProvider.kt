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
import java.net.URLEncoder

/**
 * Google Gemini (Generative Language API) provider.
 *
 * Endpoint:
 *   `https://generativelanguage.googleapis.com/v1beta/models/<model>:generateContent?key=<key>`
 *
 * The key is passed as a query string parameter (Google's convention),
 * NOT as a header. We URL-encode it defensively in case it ever contains
 * `+` or `/` (it shouldn't but the cost of encoding a clean key is zero).
 *
 * Configuration:
 *   - `apiKey`  required
 *   - `model`   defaults to `gemini-1.5-flash` (cheap + fast)
 *
 * Per-request param overrides:
 *   - `model`        switch model
 *   - `temperature`  Double, default 0.7
 *   - `max_tokens`   Int,    default 1024 (mapped to `maxOutputTokens`)
 *
 * Response shape:
 *   { "candidates": [ { "content": { "parts": [ { "text": "..." } ] } } ] }
 */
class GeminiProvider(
    private val keyProvider: () -> String?,
    private val defaultModel: String = "gemini-1.5-flash",
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta",
    private val httpClient: OkHttpClient = OpenAiProvider.defaultHttpClient(),
) : RemoteLlmConnector.Provider {

    override val label: String = "gemini"

    override fun isConfigured(): Boolean = !keyProvider().isNullOrBlank()

    override suspend fun complete(prompt: String, params: Map<String, String>): String =
        withContext(Dispatchers.IO) {
            val key = keyProvider()
                ?: throw IOException("Gemini key missing")

            val model = params["model"] ?: defaultModel

            val body = JsonObject().apply {
                add("contents", Gson().toJsonTree(listOf(
                    mapOf("parts" to listOf(mapOf("text" to prompt))),
                )))
                add("generationConfig", JsonObject().apply {
                    addProperty("temperature",      params["temperature"]?.toDoubleOrNull() ?: 0.7)
                    addProperty("maxOutputTokens",  params["max_tokens"]?.toIntOrNull()    ?: 1024)
                })
            }.toString()

            val url = "${baseUrl.trimEnd('/')}/models/${model}:generateContent" +
                "?key=${URLEncoder.encode(key, Charsets.UTF_8.name())}"

            val req = Request.Builder()
                .url(url)
                .header("Content-Type", "application/json")
                .post(body.toRequestBody(JSON))
                .build()

            httpClient.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    throw IOException("gemini http ${resp.code}: ${text.take(400)}")
                }
                extractContent(text)
            }
        }

    private fun extractContent(json: String): String {
        val root = JsonParser.parseString(json).asJsonObject
        val candidates = root.getAsJsonArray("candidates")
            ?: throw IOException("gemini: no 'candidates'")
        if (candidates.size() == 0) throw IOException("gemini: empty candidates")
        val parts = candidates[0].asJsonObject
            .getAsJsonObject("content")
            ?.getAsJsonArray("parts")
            ?: throw IOException("gemini: no 'parts'")
        val sb = StringBuilder()
        for (i in 0 until parts.size()) {
            sb.append(parts[i].asJsonObject.get("text")?.asString.orEmpty())
        }
        if (sb.isEmpty()) throw IOException("gemini: empty text parts")
        return sb.toString()
    }

    companion object {
        private val JSON = "application/json".toMediaType()
    }
}
