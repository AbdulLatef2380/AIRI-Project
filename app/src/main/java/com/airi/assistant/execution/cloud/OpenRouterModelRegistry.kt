package com.airi.assistant.execution.cloud

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Small live catalog for OpenRouter's public GET /api/v1/models endpoint.
 *
 * The catalog is advisory for automatic routing, but an explicit model is
 * never replaced: callers can reject it with a precise unavailable-model
 * error before dispatching the request.
 */
object OpenRouterModelRegistry {
    private const val MODELS_URL = "https://openrouter.ai/api/v1/models"
    private const val TTL_MS = 6 * 60 * 60 * 1000L
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 10_000

    @Volatile private var cachedIds: Set<String> = emptySet()
    @Volatile private var fetchedAtMs: Long = 0L

    // Models whose catalog entry lists "tools" in supported_parameters (report أ-5).
    @Volatile private var cachedToolIds: Set<String> = emptySet()

    fun snapshot(): Set<String> = cachedIds

    /** Live-catalog models that declare tool support; empty until a catalog with that field was read. */
    fun toolCapableSnapshot(): Set<String> = cachedToolIds

    fun contains(modelId: String): Boolean = cachedIds.isEmpty() || modelId in cachedIds

    suspend fun refresh(force: Boolean = false): Set<String> = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        if (!force && cachedIds.isNotEmpty() && now - fetchedAtMs < TTL_MS) return@withContext cachedIds

        runCatching {
            val connection = (URL(MODELS_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                setRequestProperty("Accept", "application/json")
            }
            var toolIds: Set<String> = emptySet()
            val ids = try {
                if (connection.responseCode !in 200..299) emptySet()
                else connection.inputStream.bufferedReader().use {
                    val body = it.readText()
                    toolIds = parseToolCapableIds(body)
                    parseModelIds(body)
                }
            } finally {
                connection.disconnect()
            }
            if (ids.isNotEmpty()) {
                cachedIds = ids
                cachedToolIds = toolIds
                fetchedAtMs = now
            }
            cachedIds
        }.getOrElse {
            // Keep the last verified catalog. A temporary registry outage must
            // not turn a previously valid configured provider into a different one.
            cachedIds
        }
    }

    internal fun parseToolCapableIds(body: String): Set<String> = runCatching {
        val data = JSONObject(body).optJSONArray("data") ?: return@runCatching emptySet()
        buildSet {
            for (index in 0 until data.length()) {
                val model = data.optJSONObject(index) ?: continue
                val id = model.optString("id").trim().takeIf { it.isNotBlank() } ?: continue
                val params = model.optJSONArray("supported_parameters") ?: continue
                for (i in 0 until params.length()) {
                    if (params.optString(i) == "tools") { add(id); break }
                }
            }
        }
    }.getOrDefault(emptySet())

    internal fun parseModelIds(body: String): Set<String> = runCatching {
        val data = JSONObject(body).optJSONArray("data") ?: return@runCatching emptySet()
        buildSet {
            for (index in 0 until data.length()) {
                data.optJSONObject(index)?.optString("id")
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?.let(::add)
            }
        }
    }.getOrDefault(emptySet())
}
