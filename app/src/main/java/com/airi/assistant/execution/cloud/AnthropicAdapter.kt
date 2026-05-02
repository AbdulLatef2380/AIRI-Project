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
 * Production-grade Anthropic Claude streaming adapter.
 *
 * ## Wire protocol
 * Uses the Anthropic Messages API with SSE streaming:
 *   POST https://api.anthropic.com/v1/messages
 *   anthropic-version: 2023-06-01
 *   x-api-key: {API_KEY}
 *   Content-Type: application/json
 *
 * ## SSE event types (Anthropic-specific, NOT OpenAI-compatible)
 *  - `content_block_delta` with `delta.text` — incremental token
 *  - `message_delta` with `usage.output_tokens` — completion count
 *  - `message_start` with `message.usage.input_tokens` — prompt count
 *  - `message_stop` — stream end sentinel
 *
 * ## Token usage
 * Prompt tokens from `message_start.message.usage.input_tokens`.
 * Completion tokens from `message_delta.usage.output_tokens`.
 *
 * ## Cancellation + error handling
 * Same pattern as [GeminiAdapter]: finally block disconnects, ensureActive()
 * checked per token, [CloudErrorMapper] for normalized error types.
 */
class AnthropicAdapter(
    private val keyStore: SecureApiKeyStore,
    private val model:    String = DEFAULT_MODEL
) : CloudProviderAdapter {

    override val providerId: String = "anthropic"

    override val isAvailable: Boolean
        get() = keyStore.hasKey(CloudProvider.ANTHROPIC)

    override suspend fun streamGenerate(
        request:  ExecutionRequest,
        onToken:  suspend (String) -> Unit,
        onUsage:  suspend (Int, Int) -> Unit
    ): CloudProviderAdapter.AdapterResult = withContext(Dispatchers.IO) {

        val apiKey = keyStore.getKey(CloudProvider.ANTHROPIC)
            ?: return@withContext CloudProviderAdapter.AdapterResult.Failure(
                error     = "No Anthropic API key configured",
                errorType = CloudErrorType.UNAUTHORIZED,
                retryable = false
            )

        val body    = buildRequestBody(request)
        var conn: HttpURLConnection? = null
        val fullText       = StringBuilder()
        var promptTokens   = 0
        var completeTokens = 0
        val startMs        = System.currentTimeMillis()

        try {
            conn = (URL(ENDPOINT).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                doOutput       = true
                setRequestProperty("Content-Type",      "application/json")
                setRequestProperty("Accept",             "text/event-stream")
                setRequestProperty("x-api-key",          apiKey)
                setRequestProperty("anthropic-version",  ANTHROPIC_VERSION)
            }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $httpCode"
                val mapped  = CloudErrorMapper.map(httpCode, errBody)
                Log.w(TAG, "HTTP $httpCode: ${errBody.take(200)}")
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error     = mapped.message,
                    errorType = mapped.type,
                    retryable = mapped.retryable,
                    httpCode  = httpCode
                )
            }

            var currentEventType = ""

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                // Label the loop so inner `when` branches can `continue` it without
                // accidentally exiting the enclosing `use` lambda.
                sseLoop@ while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val raw = line!!.trim()

                    when {
                        raw.startsWith("event:") -> {
                            currentEventType = raw.removePrefix("event:").trim()
                        }
                        raw.startsWith("data:") -> {
                            val payload = raw.removePrefix("data:").trim()
                            // Blank data lines are normal SSE field separators — skip them.
                            if (payload.isBlank()) continue@sseLoop

                            when (currentEventType) {
                                "content_block_delta" -> {
                                    val token = extractDeltaText(payload)
                                    if (token.isNotEmpty()) {
                                        fullText.append(token)
                                        onToken(token)
                                    }
                                }
                                "message_start" -> {
                                    promptTokens = extractIntField(payload, "input_tokens") ?: 0
                                }
                                "message_delta" -> {
                                    completeTokens = extractIntField(payload, "output_tokens") ?: completeTokens
                                }
                                // message_stop signals end of stream — exit the use block cleanly.
                                "message_stop" -> return@use
                            }
                        }
                    }
                }
            }

            val latency = System.currentTimeMillis() - startMs
            onUsage(promptTokens, completeTokens)
            Log.i(TAG, "Complete: ${fullText.length} chars ${promptTokens}p+${completeTokens}c ${latency}ms")

            CloudProviderAdapter.AdapterResult.Success(
                fullText         = fullText.toString(),
                latencyMs        = latency,
                promptTokens     = promptTokens,
                completionTokens = completeTokens
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Cancelled after ${fullText.length} chars")
            CloudProviderAdapter.AdapterResult.Failure(
                error = "Cancelled", errorType = CloudErrorType.CANCELLED,
                retryable = false, httpCode = -3
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
            Log.w(TAG, "IOException code=$code: ${e.message}")
            CloudProviderAdapter.AdapterResult.Failure(
                error = mapped.message, errorType = mapped.type,
                retryable = mapped.retryable, httpCode = code
            )
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    // ── Request builder ───────────────────────────────────────────────────────

    private fun buildRequestBody(req: ExecutionRequest): String = buildString {
        append("{")
        append("\"model\":${jsonString(model)},")
        append("\"max_tokens\":${req.maxTokens},")

        if (req.systemPrompt.isNotBlank()) {
            append("\"system\":${jsonString(req.systemPrompt)},")
        }

        append("\"messages\":[{\"role\":\"user\",\"content\":${jsonString(req.prompt)}}],")
        append("\"stream\":true")
        append("}")
    }

    // ── Anthropic SSE parsers ─────────────────────────────────────────────────

    private fun extractDeltaText(json: String): String {
        val deltaIdx = json.indexOf("\"delta\"")
        val fromIdx  = if (deltaIdx >= 0) deltaIdx else 0
        val textIdx  = json.indexOf("\"text\"", fromIdx)
        if (textIdx < 0) return ""
        val colonIdx = json.indexOf(":", textIdx)
        if (colonIdx < 0) return ""
        val afterColon = json.substring(colonIdx + 1).trimStart()
        if (!afterColon.startsWith("\"")) return ""
        val e = findStringEnd(afterColon, 1)
        if (e < 0) return ""
        return afterColon.substring(1, e)
            .replace("\\n", "\n").replace("\\\"", "\"")
            .replace("\\\\", "\\").replace("\\t", "\t")
    }

    private fun extractIntField(json: String, field: String): Int? {
        val idx = json.indexOf("\"$field\"")
        if (idx < 0) return null
        val colonIdx = json.indexOf(":", idx)
        if (colonIdx < 0) return null
        val after = json.substring(colonIdx + 1).trimStart()
        return after.takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun findStringEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when { s[i] == '\\' -> i += 2; s[i] == '"' -> return i; else -> i++ }
        }
        return -1
    }

    private fun jsonString(s: String): String =
        "\"${s.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n").replace("\t","\\t")}\""

    companion object {
        private const val TAG               = "AIRI_AnthropicAdapter"
        private const val ENDPOINT          = "https://api.anthropic.com/v1/messages"
        private const val ANTHROPIC_VERSION = "2023-06-01"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 90_000
        const val DEFAULT_MODEL             = "claude-haiku-4-5"
    }
}
