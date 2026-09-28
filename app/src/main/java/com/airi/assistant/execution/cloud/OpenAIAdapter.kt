package com.airi.assistant.execution.cloud

import android.util.Log
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
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

        if (provider != CloudProvider.OPENAI && request.inlineDataParts.isNotEmpty()) {
            request.attachmentTrace?.reject("This OpenAI-compatible provider has no native file transport.")
            return@withContext CloudProviderAdapter.AdapterResult.Failure(
                error = "File attachments are not implemented for ${provider.displayName}",
                errorType = CloudErrorType.INVALID_REQUEST,
                retryable = false,
            )
        }

        if (provider == CloudProvider.OPENAI && OpenAIResponsesPayloadContract.requiresResponses(request)) {
            return@withContext streamResponses(request, apiKey, onToken, onUsage)
        }

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
            // HttpURLConnection.readLine() is blocking; coroutine cancellation
            // alone does not interrupt it. Close this exact attempt's socket.
            currentCoroutineContext()[Job]?.invokeOnCompletion { conn?.disconnect() }

            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $httpCode"
                val mapped  = CloudErrorMapper.map(httpCode, errBody)
                Log.w(TAG, "CLOUD_HTTP_FAILURE provider=$providerId code=$httpCode errorType=${mapped.type}")
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error     = mapped.message,
                    errorType = mapped.type,
                    retryable = mapped.retryable,
                    httpCode  = httpCode
                )
            }

            // ── Parse SSE stream ───────────────────────────────────────────
            var streamFailure: CloudProviderAdapter.AdapterResult.Failure? = null
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var sawDone = false
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()   // Cooperative cancellation
                    val raw = line!!.trim()
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload == "[DONE]") {
                        sawDone = true
                        break
                    }
                    if (payload.isBlank()) continue

                    val event = OpenAISseParser.parse(payload)
                    if (event.malformed) {
                        streamFailure = CloudProviderAdapter.AdapterResult.Failure(
                            error = "Malformed OpenAI stream event",
                            errorType = CloudErrorType.UNKNOWN,
                            retryable = false,
                            httpCode = 200
                        )
                        break
                    }
                    if (event.hasProviderError) {
                        val mapped = CloudErrorMapper.mapStructuredProviderError(
                            code = event.errorCode,
                            type = event.errorType,
                        )
                        streamFailure = CloudProviderAdapter.AdapterResult.Failure(
                            error = mapped.message,
                            errorType = mapped.type,
                            retryable = mapped.retryable,
                            httpCode = 200
                        )
                        break
                    }

                    // Token delta
                    val token = event.text
                    if (token.isNotEmpty()) {
                        fullText.append(token)
                        onToken(token)
                    }

                    // Usage (present in the final chunk when stream_options.include_usage=true)
                    event.promptTokens?.let { promptTokens = it }
                    event.completionTokens?.let { completeTokens = it }
                }
                streamFailure?.let { return@withContext it }
                if (!sawDone) {
                    return@withContext CloudProviderAdapter.AdapterResult.Failure(
                        error = "OpenAI stream ended before [DONE]",
                        errorType = CloudErrorType.CONNECTION_LOST,
                        retryable = fullText.isEmpty(),
                        httpCode = -2
                    )
                }
            }

            if (fullText.isBlank()) {
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error = "Provider returned no text",
                    errorType = CloudErrorType.UNKNOWN,
                    retryable = false,
                    httpCode = 200
                )
            }

            val latency = System.currentTimeMillis() - startMs
            onUsage(promptTokens, completeTokens)
            Log.i(TAG, "[$providerId] complete: ${fullText.length} chars ${promptTokens}p+${completeTokens}c ${latency}ms")

            CloudProviderAdapter.AdapterResult.Success(
                fullText         = fullText.toString(),
                latencyMs        = latency,
                promptTokens     = promptTokens,
                completionTokens = completeTokens,
                executedModelId  = model
            )

        } catch (e: kotlinx.coroutines.CancellationException) {
            Log.i(TAG, "[$providerId] stream cancelled after ${fullText.length} chars")
            throw e
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

    /** Native OpenAI Responses transport used only by the first-party OpenAI provider. */
    private suspend fun streamResponses(
        request: ExecutionRequest,
        apiKey: String,
        onToken: suspend (String) -> Unit,
        onUsage: suspend (Int, Int) -> Unit,
    ): CloudProviderAdapter.AdapterResult {
        val endpoint = "$baseUrl/responses"
        val body = OpenAIResponsesPayloadContract.buildRequestBody(request, model)
        val attachmentIds = OpenAIResponsesPayloadContract.attachmentIds(request)
        val trace = request.attachmentTrace
        if (!OpenAIResponsesPayloadContract.containsNonEmptyContent(request)) {
            trace?.reject("OpenAI attachment payload contained empty content.")
            return CloudProviderAdapter.AdapterResult.Failure(
                error = "OpenAI attachment payload is empty",
                errorType = CloudErrorType.INVALID_REQUEST,
                retryable = false,
            )
        }
        trace?.mark(AttachmentDeliveryStage.PROVIDER_PAYLOAD_BUILT, attachmentIds)
        request.imageParts.forEach { trace?.markPayloadContainsContent(it.attachmentId, it.base64Data.length.toLong()) }
        request.inlineDataParts.forEach { trace?.markPayloadContainsContent(it.attachmentId, it.base64Data.length.toLong()) }

        var conn: HttpURLConnection? = null
        val fullText = StringBuilder()
        var promptTokens = 0
        var completeTokens = 0
        var sawDone = false
        val startMs = System.currentTimeMillis()
        try {
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            currentCoroutineContext()[Job]?.invokeOnCompletion { conn?.disconnect() }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            trace?.mark(AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED, attachmentIds)
            val httpCode = conn.responseCode
            if (httpCode !in 200..299) {
                trace?.markProviderResponse(false, attachmentIds)
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $httpCode"
                val mapped = CloudErrorMapper.map(httpCode, errBody)
                return CloudProviderAdapter.AdapterResult.Failure(
                    error = mapped.message,
                    errorType = mapped.type,
                    retryable = mapped.retryable,
                    httpCode = httpCode,
                )
            }
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val raw = line!!.trim()
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload.isBlank()) continue
                    val eventType = jsonStringField(payload, "type")
                    when (eventType) {
                        "response.output_text.delta" -> {
                            val token = jsonStringField(payload, "delta").orEmpty()
                            if (token.isNotEmpty()) {
                                fullText.append(token)
                                onToken(token)
                            }
                        }
                        "response.completed", "response.done" -> {
                            promptTokens = jsonIntField(payload, "input_tokens") ?: promptTokens
                            completeTokens = jsonIntField(payload, "output_tokens") ?: completeTokens
                            sawDone = true
                            break
                        }
                        "response.failed", "error" -> {
                            trace?.markProviderResponse(false, attachmentIds)
                            return CloudProviderAdapter.AdapterResult.Failure(
                                error = "OpenAI Responses stream returned an error",
                                errorType = CloudErrorType.UNKNOWN,
                                retryable = false,
                                httpCode = 200,
                            )
                        }
                    }
                }
            }
            if (!sawDone) {
                trace?.markProviderResponse(false, attachmentIds)
                return CloudProviderAdapter.AdapterResult.Failure(
                    error = "OpenAI Responses stream ended before completion",
                    errorType = CloudErrorType.CONNECTION_LOST,
                    retryable = fullText.isEmpty(),
                    httpCode = -2,
                )
            }
            if (fullText.isBlank()) {
                trace?.markProviderResponse(false, attachmentIds)
                return CloudProviderAdapter.AdapterResult.Failure(
                    error = "OpenAI returned no text",
                    errorType = CloudErrorType.UNKNOWN,
                    retryable = false,
                    httpCode = 200,
                )
            }
            trace?.mark(AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED, attachmentIds)
            trace?.markProviderResponse(true, attachmentIds)
            val latency = System.currentTimeMillis() - startMs
            onUsage(promptTokens, completeTokens)
            CloudProviderAdapter.AdapterResult.Success(
                fullText = fullText.toString(),
                latencyMs = latency,
                promptTokens = promptTokens,
                completionTokens = completeTokens,
                executedModelId = model,
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            trace?.markProviderResponse(false, attachmentIds)
            val mapped = CloudErrorMapper.map(-1, e.message ?: "timeout")
            CloudProviderAdapter.AdapterResult.Failure(mapped.message, mapped.type, mapped.retryable, -1)
        } catch (e: java.io.IOException) {
            trace?.markProviderResponse(false, attachmentIds)
            val mapped = CloudErrorMapper.map(-1, e.message ?: "io error")
            CloudProviderAdapter.AdapterResult.Failure(mapped.message, mapped.type, mapped.retryable, -1)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun jsonStringField(json: String, field: String): String? {
        val key = "\"$field\""
        val keyIndex = json.indexOf(key)
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex + key.length)
        if (colon < 0) return null
        val start = json.indexOf('"', colon + 1)
        if (start < 0) return null
        var i = start + 1
        val result = StringBuilder()
        while (i < json.length) {
            when (json[i]) {
                '\\' -> {
                    if (i + 1 >= json.length) return null
                    result.append when (json[i + 1]) {
                        'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'; else -> json[i + 1]
                    }
                    i += 2
                }
                '"' -> return result.toString()
                else -> result.append(json[i++])
            }
        }
        return null
    }

    private fun jsonIntField(json: String, field: String): Int? {
        val keyIndex = json.indexOf("\"$field\"")
        if (keyIndex < 0) return null
        val colon = json.indexOf(':', keyIndex)
        if (colon < 0) return null
        return json.substring(colon + 1).trimStart().takeWhile { it.isDigit() }.toIntOrNull()
    }

    // ── Subclass extension point ──────────────────────────────────────────────

    /** Subclasses (e.g. OpenRouter) override to inject provider-specific headers. */
    protected open fun applyExtraHeaders(conn: HttpURLConnection) = Unit

    // ── Request builder ───────────────────────────────────────────────────────

    /**
     * Build OpenAI Chat Completions request with full conversation history.
     * OpenAI is stateless — all prior turns must be resent on every call.
     * Roles: "user" and "assistant" (not "model" like Gemini).
     */
    private fun buildRequestBody(req: ExecutionRequest): String = buildString {
        append("{")
        append("\"model\":${jsonString(model)},")
        append("\"messages\":[")
        var needsComma = false
        if (req.systemPrompt.isNotBlank()) {
            append("{\"role\":\"system\",\"content\":${jsonString(req.systemPrompt)}}")
            needsComma = true
        }
        for (turn in req.conversationHistory) {
            if (needsComma) append(",")
            append("{\"role\":\"${turn.role}\",\"content\":${jsonString(turn.content)}}")
            needsComma = true
        }
        if (req.prompt.isNotBlank() || req.imageParts.isNotEmpty()) {
            if (needsComma) append(",")
            append("{\"role\":\"user\",\"content\":")
            if (req.imageParts.isEmpty()) {
                append(jsonString(req.prompt))
            } else {
                append("[")
                var hasPart = false
                if (req.prompt.isNotBlank()) {
                    append("{\"type\":\"text\",\"text\":${jsonString(req.prompt)}}")
                    hasPart = true
                }
                req.imageParts.forEach { image ->
                    if (hasPart) append(",")
                    append("{\"type\":\"image_url\",\"image_url\":{\"url\":")
                    append(jsonString("data:${image.mimeType.ifBlank { "image/jpeg" }};base64,${image.base64Data}"))
                    append("}}")
                    hasPart = true
                }
                append("]")
            }
            append("}")
        }
        append("],")
        append("\"max_tokens\":${req.maxTokens},")
        append("\"temperature\":${req.temperature},")
        append("\"stream\":true,")
        append("\"stream_options\":{\"include_usage\":true}")
        append("}")
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
