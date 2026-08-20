package com.airi.assistant.execution.cloud

import android.util.Log
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.security.SecureApiKeyStore
import com.airi.assistant.execution.CloudProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

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
    private val model:    String = "gemini-2.0-flash"
) : CloudProviderAdapter {

    override val providerId: String = "gemini"
    override val isAvailable: Boolean get() = keyStore.hasKey(CloudProvider.GEMINI)

    override suspend fun streamGenerate(
        request: ExecutionRequest,
        onToken: suspend (String) -> Unit,
        onUsage: suspend (Int, Int) -> Unit
    ): CloudProviderAdapter.AdapterResult = withContext(Dispatchers.IO) {

        val apiKey = keyStore.getKey(CloudProvider.GEMINI)
            ?: return@withContext CloudProviderAdapter.AdapterResult.Failure(
                error = "No Gemini API key configured",
                errorType = CloudErrorType.UNAUTHORIZED,
                retryable = false
            )

        val url  = "$BASE_URL/models/$model:streamGenerateContent?alt=sse&key=$apiKey"
        val body = buildRequestBody(request)

        Log.d(TAG, "streamGenerate model=$model " +
            "history=${request.conversationHistory.size} prompt_chars=${request.prompt.length}")

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
                Log.w(TAG, "CLOUD_HTTP_FAILURE provider=gemini code=$httpCode errorType=${mapped.type}")
                return@withContext CloudProviderAdapter.AdapterResult.Failure(
                    error = mapped.message, errorType = mapped.type,
                    retryable = mapped.retryable, httpCode = httpCode
                )
            }

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    ensureActive()
                    val raw = line!!.trim()
                    if (!raw.startsWith("data:")) continue
                    val payload = raw.removePrefix("data:").trim()
                    if (payload.isBlank() || payload == "[DONE]") continue
                    val token = extractToken(payload)
                    if (token.isNotEmpty()) { fullText.append(token); onToken(token) }
                    extractUsage(payload)?.let { (p, c) -> promptTokens = p; completeTokens = c }
                }
            }

            onUsage(promptTokens, completeTokens)
            val latency = System.currentTimeMillis() - startMs
            Log.i(TAG, "complete: ${fullText.length} chars ${promptTokens}p+${completeTokens}c ${latency}ms")
            CloudProviderAdapter.AdapterResult.Success(
                fullText = fullText.toString(), latencyMs = latency,
                promptTokens = promptTokens, completionTokens = completeTokens
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            CloudProviderAdapter.AdapterResult.Failure("Cancelled", CloudErrorType.CANCELLED, false, -3)
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
        if (!first) append(",")
        append("{\"role\":\"user\",\"parts\":[{\"text\":")
        append(jsonString(req.prompt))
        append("}]},")
        append("\"generationConfig\":{\"maxOutputTokens\":${req.maxTokens},\"temperature\":${req.temperature}}")
        append("}")
    }

    private fun extractToken(json: String): String {
        val idx = json.indexOf("\"text\"")
        if (idx < 0) return ""
        val ci = json.indexOf(":", idx)
        if (ci < 0) return ""
        val after = json.substring(ci + 1).trimStart()
        if (!after.startsWith("\"")) return ""
        val e = findStringEnd(after, 1)
        if (e < 0) return ""
        return after.substring(1, e)
            .replace("\\n", "\n").replace("\\\"", "\"")
            .replace("\\\\", "\\").replace("\\t", "\t")
    }

    private fun extractUsage(json: String): Pair<Int, Int>? {
        if (!json.contains("usageMetadata")) return null
        val p = extractInt(json, "promptTokenCount")     ?: return null
        val c = extractInt(json, "candidatesTokenCount") ?: 0
        return p to c
    }

    private fun extractInt(json: String, field: String): Int? {
        val idx = json.indexOf("\"$field\""); if (idx < 0) return null
        val ci  = json.indexOf(":", idx);    if (ci < 0) return null
        return json.substring(ci + 1).trimStart().takeWhile { it.isDigit() }.toIntOrNull()
    }

    private fun findStringEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length) { when { s[i] == '\\' -> i += 2; s[i] == '"' -> return i; else -> i++ } }
        return -1
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
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS    = 90_000
    }
}
