package com.airi.assistant.execution.cloud

import android.util.Log
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production-grade OpenAI-compatible streaming adapter.
 *
 * Handles:
 *  - OpenAI      (api.openai.com/v1)
 *  - Moonshot Kimi (api.moonshot.cn/v1)  — OpenAI-compatible
 *  - Custom endpoints                    — any OpenAI-compatible server
 *
 * OpenRouter has its own subclass [OpenRouterAdapter] which overrides
 * [baseUrl] and injects extra headers.
 *
 * ## Wire protocol
 * Standard OpenAI Chat Completions SSE:
 *   POST {baseUrl}/chat/completions
 *   Authorization: Bearer {API_KEY}
 *   Content-Type: application/json
 *
 * Request body: `{"model":..., "messages":[...], "stream":true,
 *   "stream_options":{"include_usage":true}, "max_tokens":..., "temperature":...}`
 *
 * `stream_options.include_usage` requests the token count in the final SSE
 * chunk so we don't have to estimate it.
 *
 * ## Token usage
 * The final non-[DONE] chunk carries `usage.prompt_tokens` and
 * `usage.completion_tokens`. Both are reported to [onUsage].
 *
 * ## Cancellation
 * The HTTP connection is always disconnected in a `finally` block.
 * [kotlinx.coroutines.ensureActive] is checked after each token.
 */
open class OpenAIAdapter(
    protected val keyStore:    SecureApiKeyStore,
    protected val provider:    CloudProvider,
    protected open val baseUrl: String  = providerBaseUrl(provider),
    protected open val model:   String  = providerDefaultModel(provider)
) : CloudProviderAdapter {

    override val providerId: String = provider.name.lowercase()

    override val isAvailable: Boolean
        get() = keyStore.hasKey(provider)

    override suspend fun streamGenerate(
        request:  ExecutionRequest,
        onToken:  suspend (String) -> Unit,
        onUsage:  suspend (Int, Int) -> Unit
    ): CloudProviderAdapter.AdapterResult = withContext(Dispatchers.IO) {

        val apiKey = keyStore.getKey(provider)
            ?: return@withContext CloudProviderAdapter.AdapterResult.Failure(
                error     = "No ${provider.displayName} API key configured",
                errorType = CloudErrorType.UNAUTHORIZED,
                retryable = false
            )

        val endpoint = "$baseUrl/chat/completions"
        val body     = buildRequestBody(request)

        var conn: HttpURLConnection? = null
        val fullText       = StringBuilder()
        var promptTokens   = 0
        var completeTokens = 0
        val startMs        = System.currentTimeMillis()

        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                doOutput       = true
                setRequestProperty("Content-Type",  "application/json")
                setRequestProperty("Accept",         "text/event-stream")
                setRequestProperty("Authorization", "Bearer $apiKey")
                applyExtraHeaders(this)
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $httpCode"
                val mapped  = CloudErrorMapper.map(httpCode, errBody)
                Log.w(TAG, "[$providerId] HTTP $httpCode: ${errBody.take(200)}")
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error     = mapped.message,
                    errorType = mapped.type,
                    retryable = mapped.retryable,
                    httpCode  = httpCode
                )
            }

            // ── Parse SSE stream ───────────────────────────────────────────
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()   // Cooperative cancellation
                    val raw = line!!.trim()
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload == "[DONE]" || payload.isBlank()) continue

                    // Token delta
                    val token = extractDeltaContent(payload)
                    if (token.isNotEmpty()) {
                        fullText.append(token)
                        onToken(token)
                    }

                    // Usage (present in the final chunk when stream_options.include_usage=true)
                    extractUsage(payload)?.let { (p, c) ->
                        promptTokens   = p
                        completeTokens = c
                    }
                }
            }

            val latency = System.currentTimeMillis() - startMs
            onUsage(promptTokens, completeTokens)
            Log.i(TAG, "[$providerId] complete: ${fullText.length} chars ${promptTokens}p+${completeTokens}c ${latency}ms")

            CloudProviderAdapter.AdapterResult.Success(
                fullText         = fullText.toString(),
                latencyMs        = latency,
                promptTokens     = promptTokens,
                completionTokens = completeTokens
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "[$providerId] stream cancelled after ${fullText.length} chars")
            CloudProviderAdapter.AdapterResult.Failure(
                error     = "Cancelled",
                errorType = CloudErrorType.CANCELLED,
                retryable = false,
                httpCode  = -3
            )
        } catch (e: java.net.SocketTimeoutException) {
            val mapped = CloudErrorMapper.map(-1, e.message ?: "timeout")
            CloudProviderAdapter.AdapterResult.Failure(
                error = mapped.message, errorType = mapped.type,
                retryable = mapped.retryable, httpCode = -1
            )
        } catch (e: java.io.IOException) {
            val code = if (fullText.isNotEmpty()) -2 else -1
            val mapped = CloudErrorMapper.map(code, e.message ?: "io error")
            Log.w(TAG, "[$providerId] IOException code=$code: ${e.message}")
            CloudProviderAdapter.AdapterResult.Failure(
                error = mapped.message, errorType = mapped.type,
                retryable = mapped.retryable, httpCode = code
            )
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Subclass extension point ──────────────────────────────────────────────

    /** Subclasses (e.g. OpenRouter) override to inject provider-specific headers. */
    protected open fun applyExtraHeaders(conn: HttpURLConnection) = Unit

    // ── Request builder ───────────────────────────────────────────────────────

    private fun buildRequestBody(req: ExecutionRequest): String = buildString {
        append("{")
        append("\"model\":${jsonString(model)},")

        // Messages array
        append("\"messages\":[")
        if (req.systemPrompt.isNotBlank()) {
            append("{\"role\":\"system\",\"content\":${jsonString(req.systemPrompt)}},")
        }
        append("{\"role\":\"user\",\"content\":${jsonString(req.prompt)}}")
        append("],")

        append("\"max_tokens\":${req.maxTokens},")
        append("\"temperature\":${req.temperature},")
        append("\"stream\":true,")
        // Request usage in the final chunk (OpenAI >= 2024-09 + compatible)
        append("\"stream_options\":{\"include_usage\":true}")
        append("}")
    }

    // ── OpenAI SSE parsers ────────────────────────────────────────────────────

    /**
     * Extract delta.content from an OpenAI SSE payload chunk.
     */
    private fun extractDeltaContent(json: String): String {
        val deltaIdx = json.indexOf("\"delta\"")
        val searchFrom = if (deltaIdx >= 0) deltaIdx else 0
        val contentIdx = json.indexOf("\"content\"", searchFrom)
        if (contentIdx < 0) return ""
        val colonIdx = json.indexOf(":", contentIdx)
        if (colonIdx < 0) return ""
        val afterColon = json.substring(colonIdx + 1).trimStart()
        // null content = role-only delta
        if (afterColon.startsWith("null")) return ""
        if (!afterColon.startsWith("\"")) return ""
        val s = 1
        val e = findStringEnd(afterColon, s)
        if (e < 0) return ""
        return afterColon.substring(s, e)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\t", "\t")
            .replace("\\r", "\r")
    }

    /**
     * Extract usage from an OpenAI SSE chunk.
     * Returns (promptTokens, completionTokens) or null.
     */
    private fun extractUsage(json: String): Pair<Int, Int>? {
        if (!json.contains("\"usage\"")) return null
        val usageIdx = json.indexOf("\"usage\"")
        val prompt   = extractIntAfterKey(json, "\"prompt_tokens\"",     usageIdx)     ?: return null
        val complete = extractIntAfterKey(json, "\"completion_tokens\"", usageIdx) ?: 0
        return Pair(prompt, complete)
    }

    private fun extractIntAfterKey(json: String, key: String, fromIdx: Int): Int? {
        val idx = json.indexOf(key, fromIdx)
        if (idx < 0) return null
        val colonIdx = json.indexOf(":", idx)
        if (colonIdx < 0) return null
        val after = json.substring(colonIdx + 1).trimStart()
        val numStr = after.takeWhile { it.isDigit() }
        return numStr.toIntOrNull()
    }

    private fun findStringEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when {
                s[i] == '\\' -> i += 2
                s[i] == '"'  -> return i
                else         -> i++
            }
        }
        return -1
    }

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\t", "\\t").replace("\r", "\\r")}\""

    companion object {
        private const val TAG              = "AIRI_OpenAIAdapter"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 90_000

        fun providerBaseUrl(provider: CloudProvider): String = when (provider) {
            CloudProvider.OPENAI     -> "https://api.openai.com/v1"
            CloudProvider.KIMI       -> "https://api.moonshot.cn/v1"
            CloudProvider.CUSTOM     -> ""   // overridden by CloudAdapterFactory
            else                     -> "https://api.openai.com/v1"
        }

        fun providerDefaultModel(provider: CloudProvider): String = when (provider) {
            CloudProvider.OPENAI     -> "gpt-4o-mini"
            CloudProvider.KIMI       -> "moonshot-v1-8k"
            CloudProvider.CUSTOM     -> "gpt-4o-mini"
            else                     -> "gpt-4o-mini"
        }
    }
}
