package com.airi.assistant.execution.cloud

import android.util.Log
import com.airi.assistant.execution.AttachmentDeliveryStage
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.execution.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

private val GEMINI_BLOCKING_FINISH_REASONS = setOf(
    "SAFETY", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "IMAGE_SAFETY"
)

/**
 * Gemini streaming adapter — multi-turn REST implementation.
 *
 * The Gemini API is stateless. Every request must carry the full conversation
 * history in `contents` with alternating user/model role entries. Previously
 * only the current user message was sent, breaking every second prompt.
 *
 * [ExecutionRequest.conversationHistory] carries prior turns. "assistant" role
 * is mapped to "model" per the Gemini API specification.
 */
class GeminiAdapter(
    private val keyStore: SecureApiKeyStore,
    /** Stable Google alias; avoids shipping a version that may not be enabled for a key. */
    private val model:    String = DEFAULT_MODEL
) : CloudProviderAdapter {

    override val providerId: String = "gemini"
    override val isAvailable: Boolean get() = keyStore.hasKey(CloudProvider.GEMINI)

    override suspend fun streamGenerate(
        request: ExecutionRequest,
        onToken: suspend (String) -> Unit,
        onUsage: suspend (Int, Int) -> Unit
    ): CloudProviderAdapter.AdapterResult = withContext(Dispatchers.IO) {

        request.attachmentTrace?.setProvider(CloudProvider.GEMINI)
        val apiKey = keyStore.getKey(CloudProvider.GEMINI)
            ?: return@withContext CloudProviderAdapter.AdapterResult.Failure(
                error = "No Gemini API key configured",
                errorType = CloudErrorType.UNAUTHORIZED,
                retryable = false
            )

        // Keep credentials out of URLs: proxies, logs and diagnostics commonly
        // retain request URLs. Gemini supports x-goog-api-key authentication.
        val url  = "$BASE_URL/models/$model:streamGenerateContent?alt=sse"
        val body = buildRequestBody(request)
        val trace = request.attachmentTrace
        val attachmentIds = (request.imageParts.map { it.attachmentId } +
            request.inlineDataParts.map { it.attachmentId }).filter { it.isNotBlank() }
        trace?.mark(AttachmentDeliveryStage.PROVIDER_PAYLOAD_BUILT, attachmentIds)
        attachmentIds.forEach { id ->
            val encodedLength = request.imageParts.firstOrNull { it.attachmentId == id }?.base64Data?.length
                ?: request.inlineDataParts.firstOrNull { it.attachmentId == id }?.base64Data?.length
                ?: 0
            if (encodedLength > 0 && body.contains("\"data\":\"") &&
                GeminiPayloadContract.containsNonEmptyInlineContent(request)) {
                trace?.markPayloadContainsContent(id, encodedLength.toLong())
            }
        }

        Log.d(TAG, "streamGenerate model=$model " +
            "history=${request.conversationHistory.size} prompt_chars=${request.prompt.length}")

        var conn: HttpURLConnection? = null
        val fullText       = StringBuilder()
        var lastPayload    = ""
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
                setRequestProperty("x-goog-api-key", apiKey)
            }
            // readLine() blocks independently of coroutine cancellation; close
            // this request's connection when its owning job is cancelled.
            currentCoroutineContext()[Job]?.invokeOnCompletion { conn?.disconnect() }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            trace?.mark(AttachmentDeliveryStage.HTTP_REQUEST_DISPATCHED, attachmentIds)

            val httpCode = conn.responseCode
            trace?.mark(AttachmentDeliveryStage.PROVIDER_RESPONSE_RECEIVED, attachmentIds)
            if (httpCode !in 200..299) {
                trace?.markProviderResponse(false, attachmentIds)
                val errBody = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $httpCode"
                val mapped  = CloudErrorMapper.map(httpCode, errBody)
                Log.w(TAG, "CLOUD_HTTP_FAILURE provider=gemini code=$httpCode errorType=${mapped.type}")
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error = mapped.message, errorType = mapped.type,
                    retryable = mapped.retryable, httpCode = httpCode
                )
            }

            var sawTerminal = false
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val raw = line!!.trim()
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") continue
                    lastPayload = payload
                    val event = GeminiSseParser.parse(payload)
                    if (event.malformed) {
                        return@withContext CloudProviderAdapter.AdapterResult.Failure(
                            error = "Malformed Gemini stream event",
                            errorType = CloudErrorType.UNKNOWN,
                            retryable = false,
                            httpCode = 200,
                        )
                    }
                    if (event.hasError) {
                        val mapped = CloudErrorMapper.mapStructuredProviderError(
                            code = event.errorCode,
                            status = event.errorStatus,
                        )
                        return@withContext CloudProviderAdapter.AdapterResult.Failure(
                            error = mapped.message,
                            errorType = mapped.type,
                            retryable = mapped.retryable,
                            httpCode = 200
                        )
                    }
                    if (event.finishReason?.uppercase()?.let { it in GEMINI_BLOCKING_FINISH_REASONS } == true) {
                        return@withContext CloudProviderAdapter.AdapterResult.Failure(
                            error = "Gemini blocked the response by content policy",
                            errorType = CloudErrorType.CONTENT_FILTERED,
                            retryable = false,
                            httpCode = 200,
                        )
                    }
                    if (event.terminal) sawTerminal = true
                    val token = event.text
                    if (token.isNotEmpty()) { fullText.append(token); onToken(token) }
                    event.promptTokens?.let { promptTokens = it }
                    event.completionTokens?.let { completeTokens = it }
                }
            }

            if (!sawTerminal) {
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error = "Gemini stream ended before a terminal finish reason",
                    errorType = CloudErrorType.CONNECTION_LOST,
                    retryable = fullText.isEmpty(),
                    httpCode = -2,
                )
            }

            if (fullText.isBlank()) {
                trace?.markProviderResponse(false, attachmentIds)
                val mapped = CloudErrorMapper.map(200, lastPayload)
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error = if (mapped.type == CloudErrorType.UNKNOWN) "Provider returned no text" else mapped.message,
                    errorType = mapped.type,
                    retryable = mapped.retryable,
                    httpCode = 200
                )
            }
            trace?.markProviderResponse(true, attachmentIds)
            trace?.snapshot()?.forEach { evidence ->
                Log.i(
                    TAG,
                    "ATTACHMENT_DELIVERY id=${evidence.attachmentId} " +
                        "status=${evidence.status} transport=${evidence.transport} " +
                        "stages=${evidence.stages} bytes=${evidence.bytesIncluded}",
                )
            }
            onUsage(promptTokens, completeTokens)
            val latency = System.currentTimeMillis() - startMs
            Log.i(TAG, "complete: ${fullText.length} chars ${promptTokens}p+${completeTokens}c ${latency}ms")
            CloudProviderAdapter.AdapterResult.Success(
                fullText = fullText.toString(), latencyMs = latency,
                promptTokens = promptTokens, completionTokens = completeTokens,
                executedModelId = model
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: java.net.SocketTimeoutException) {
            val m = CloudErrorMapper.map(-1, e.message ?: "timeout")
            CloudProviderAdapter.AdapterResult.Failure(m.message, m.type, m.retryable, -1)
        } catch (e: java.io.IOException) {
            val m = CloudErrorMapper.map(-2, e.message ?: "io error")
            CloudProviderAdapter.AdapterResult.Failure(m.message, m.type, m.retryable, -2)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun buildRequestBody(req: ExecutionRequest): String = buildString {
        append("{")
        if (req.systemPrompt.isNotBlank()) {
            append("\"systemInstruction\":{\"parts\":[{\"text\":")
            append(jsonString(req.systemPrompt))
            append("}]},")
        }
        append("\"contents\":[")
        var first = true
        for (turn in req.conversationHistory) {
            if (!first) append(",")
            first = false
            val role = if (turn.role == "assistant") "model" else "user"
            append("{\"role\":\"$role\",\"parts\":[{\"text\":")
            append(jsonString(turn.content))
            append("}]}")
        }
        if (req.prompt.isNotBlank() || req.imageParts.isNotEmpty() || req.inlineDataParts.isNotEmpty()) {
            if (!first) append(",")
            append("{\"role\":\"user\",\"parts\":[")
            var hasPart = false
            if (req.prompt.isNotBlank()) {
                append("{\"text\":${jsonString(req.prompt)}}")
                hasPart = true
            }
            req.imageParts.forEach { image ->
                // Gemini REST expects snake_case inline_data parts. An OpenAI
                // image_url object is not valid Gemini request JSON.
                if (hasPart) append(",")
                append("{\"inline_data\":{\"mime_type\":")
                append(jsonString(image.mimeType.ifBlank { "image/jpeg" }))
                append(",\"data\":")
                append(jsonString(image.base64Data))
                append("}}")
                hasPart = true
            }
            req.inlineDataParts.forEach { part ->
                if (hasPart) append(",")
                append("{\"inline_data\":{\"mime_type\":")
                append(jsonString(part.mimeType))
                append(",\"data\":")
                append(jsonString(part.base64Data))
                append("}}")
                hasPart = true
            }
            append("]}")
        }
        append("],")
        append("\"generationConfig\":{\"maxOutputTokens\":${req.maxTokens},\"temperature\":${req.temperature}}")
        append("}")
    }

    private fun jsonString(s: String): String = buildString {
        append('"')
        for (c in s) when (c) {
            '"'      -> append("\\\"")
            '\\'     -> append("\\\\")
            '\n'     -> append("\\n")
            '\r'     -> append("\\r")
            '\t'     -> append("\\t")
            '\b'     -> append("\\b")
            '\u000C' -> append("\\f")
            else     -> if (c.code < 0x20) append("\\u%04x".format(c.code)) else append(c)
        }
        append('"')
    }

    companion object {
        private const val TAG              = "AIRI_GeminiAdapter"
        private const val BASE_URL         = "https://generativelanguage.googleapis.com/v1beta"
        const val DEFAULT_MODEL            = "gemini-flash-latest"
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 90_000
    }
}
