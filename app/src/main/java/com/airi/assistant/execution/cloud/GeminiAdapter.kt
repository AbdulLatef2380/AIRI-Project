package com.airi.assistant.execution.cloud

import android.util.Log
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.execution.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Production-grade Google Gemini streaming adapter.
 *
 * ## Wire protocol
 * Uses the Gemini REST API v1beta `streamGenerateContent` endpoint with
 * `alt=sse` query parameter. Each SSE chunk is a `data: {...}` line
 * containing a partial `GenerateContentResponse` JSON.
 *
 * Endpoint pattern:
 *   POST https://generativelanguage.googleapis.com/v1beta/models/{model}:streamGenerateContent?alt=sse&key={API_KEY}
 *
 * ## System instruction
 * Passed via the top-level `systemInstruction` field (not as a role in
 * `contents`) per the Gemini API specification. Omitted when blank.
 *
 * ## Token usage
 * The final SSE chunk contains `usageMetadata.promptTokenCount` and
 * `usageMetadata.candidatesTokenCount`. Both are reported to [onUsage].
 *
 * ## Cancellation
 * The HTTP connection is always disconnected in a `finally` block.
 * [kotlinx.coroutines.ensureActive] is checked after each token so the
 * coroutine responds to cancellation within one token's processing time.
 *
 * ## Error handling
 * HTTP errors are mapped via [CloudErrorMapper] to normalized [CloudErrorType]
 * values. Retry decisions are made by the caller ([RetryPolicy]).
 */
class GeminiAdapter(
    private val keyStore: SecureApiKeyStore,
    private val model:    String = "gemini-2.0-flash"
) : CloudProviderAdapter {

    override val providerId: String = "gemini"

    override val isAvailable: Boolean
        get() = keyStore.hasKey(CloudProvider.GEMINI)

    override suspend fun streamGenerate(
        request:  ExecutionRequest,
        onToken:  suspend (String) -> Unit,
        onUsage:  suspend (Int, Int) -> Unit
    ): CloudProviderAdapter.AdapterResult = withContext(Dispatchers.IO) {

        val apiKey = keyStore.getKey(CloudProvider.GEMINI)
            ?: return@withContext CloudProviderAdapter.AdapterResult.Failure(
                error     = "No Gemini API key configured",
                errorType = CloudErrorType.UNAUTHORIZED,
                retryable = false
            )

        val url = "$BASE_URL/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val body = buildRequestBody(request)

        var conn: HttpURLConnection? = null
        val fullText       = StringBuilder()
        var promptTokens   = 0
        var completeTokens = 0
        val startMs        = System.currentTimeMillis()

        try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout    = READ_TIMEOUT_MS
                doOutput       = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
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

            // ── Parse SSE stream ───────────────────────────────────────────
            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()   // Cooperative cancellation check
                    val raw = line!!.trim()
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") continue

                    // Extract text token
                    val token = extractGeminiToken(payload)
                    if (token.isNotEmpty()) {
                        fullText.append(token)
                        onToken(token)
                    }

                    // Extract usage from every chunk (only present in the last one)
                    extractUsage(payload)?.let { (p, c) ->
                        promptTokens   = p
                        completeTokens = c
                    }
                }
            }

            val latency = System.currentTimeMillis() - startMs
            onUsage(promptTokens, completeTokens)
            Log.i(TAG, "Stream complete: ${fullText.length} chars, ${promptTokens}p+${completeTokens}c tokens, ${latency}ms")

            CloudProviderAdapter.AdapterResult.Success(
                fullText         = fullText.toString(),
                latencyMs        = latency,
                promptTokens     = promptTokens,
                completionTokens = completeTokens
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "Stream cancelled after ${fullText.length} chars")
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
            // Check if this is a mid-stream disconnect (we already have text)
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

        // System instruction (omit if blank)
        if (req.systemPrompt.isNotBlank()) {
            append("\"systemInstruction\":{\"parts\":[{\"text\":")
            append(jsonString(req.systemPrompt))
            append("]},")
        }

        // Contents — single user turn
        append("\"contents\":[{\"role\":\"user\",\"parts\":[{\"text\":")
        append(jsonString(req.prompt))
        append("}]}],")

        // Generation config
        append("\"generationConfig\":{")
        append("\"maxOutputTokens\":${req.maxTokens},")
        append("\"temperature\":${req.temperature}")
        append("}")

        append("}")
    }

    // ── Gemini SSE parsers ────────────────────────────────────────────────────

    /**
     * Extract the text delta from a Gemini SSE payload.
     * Path: candidates[0].content.parts[0].text
     */
    private fun extractGeminiToken(json: String): String {
        // Look for "text":"..."
        val textIdx = json.indexOf("\"text\"")
        if (textIdx < 0) return ""
        val colonIdx = json.indexOf(":", textIdx)
        if (colonIdx < 0) return ""
        val afterColon = json.substring(colonIdx + 1).trimStart()
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
     * Extract usageMetadata from the final Gemini SSE chunk.
     * Returns (promptTokenCount, candidatesTokenCount) or null.
     */
    private fun extractUsage(json: String): Pair<Int, Int>? {
        if (!json.contains("usageMetadata")) return null
        val prompt     = extractIntField(json, "promptTokenCount")     ?: return null
        val candidates = extractIntField(json, "candidatesTokenCount") ?: 0
        return Pair(prompt, candidates)
    }

    private fun extractIntField(json: String, field: String): Int? {
        val idx = json.indexOf("\"$field\"")
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
        private const val TAG              = "AIRI_GeminiAdapter"
        private const val BASE_URL         = "https://generativelanguage.googleapis.com/v1beta"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 90_000
    }
}
