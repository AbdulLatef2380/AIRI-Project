package com.airi.assistant.ai.remote

import android.util.Log
import com.airi.assistant.execution.cloud.LocalEndpointPolicy
import com.airi.assistant.execution.cloud.watchConnectionForCancellation
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Legacy remote model executor for RemoteModelRegistry custom-server paths.
 *
 * Production-hardened over the original implementation:
 *  - [cancelCurrentRequest] disconnects the active [HttpURLConnection] from
 *    any thread, immediately unblocking the blocked IO read.
 *  - Coroutine cancellation (timeout or caller cancel) disconnects the socket through
 *    [watchConnectionForCancellation], which unblocks a blocked read immediately. A blocked
 *    `readLine()` ignores coroutine cancellation on its own, so this watcher is required.
 *  - [ensureActive] is also checked in the stream loop once a line has been read.
 *  - Connection stored in [@Volatile] field so [cancelCurrentRequest] sees
 *    the current connection without synchronization overhead.
 *  - Proper `finally { conn.disconnect() }` on every code path.
 *  - MAX_RETRIES increased to 2 with [RETRY_DELAY_MS] between attempts.
 *
 * This class is kept for backward compatibility with [RemoteModelRegistry]
 * custom-endpoint paths. New providers use [CloudAdapterFactory] instead.
 */
class RemoteModelExecutor {

    companion object {
        private const val TAG              = "RemoteModelExecutor"
        private const val TIMEOUT_MS       = 90_000L
        private const val CONNECT_TIMEOUT  = 8_000
        private const val READ_TIMEOUT     = 90_000
        private const val MAX_RETRIES      = 2
        private const val RETRY_DELAY_MS   = 1_500L

        /**
         * Only transient failures that happened before any token was delivered are retried:
         * network errors, HTTP 429 and 5xx. Timeouts, 4xx and policy rejections are final.
         */
        fun shouldRetryStream(error: String, tokensDelivered: Boolean): Boolean {
            if (tokensDelivered) return false
            val e = error.lowercase()
            if (e.contains("timed out after") || e.contains("rejected by local security")) return false
            val http = Regex("""^http (\d{3})""").find(e)?.groupValues?.get(1)?.toIntOrNull()
            if (http != null) return http == 429 || http in 500..599
            return true
        }
    }

    /** Current in-flight connection. Volatile for cross-thread visibility. */
    @Volatile private var activeConnection: HttpURLConnection? = null

    /**
     * Cancel the in-flight request immediately by disconnecting the socket.
     * Thread-safe. No-op if no request is in flight.
     */
    fun cancelCurrentRequest() {
        val conn = activeConnection
        if (conn != null) {
            Log.i(TAG, "cancelCurrentRequest: disconnecting active connection")
            try { conn.disconnect() } catch (e: Exception) {
                Log.w(TAG, "cancelCurrentRequest: disconnect failed: ${e.message}")
            }
        }
    }

    sealed class RemoteResult {
        data class Success(val text: String, val latencyMs: Long) : RemoteResult()
        data class Failure(val error: String)                      : RemoteResult()
    }

    suspend fun generate(
        model: RemoteModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 512,
        temperature: Float = 0.8f
    ): RemoteResult = withContext(Dispatchers.IO) {
        if (!LocalEndpointPolicy.isAllowed(model.serverUrl)) {
            return@withContext RemoteResult.Failure("Endpoint rejected by local security policy")
        }
        val startMs = System.currentTimeMillis()
        var lastError = "Unknown error"
        for (attempt in 0..MAX_RETRIES) {
            ensureActive()
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    executeRequest(model, prompt, systemPrompt, maxTokens, temperature)
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    currentCoroutineContext().ensureActive()
                    lastError = e.javaClass.simpleName
                    null
                }
            }
            if (result != null) {
                val latency = System.currentTimeMillis() - startMs
                LoggingService.debug(TAG, "Remote response in ${latency}ms (attempt ${attempt + 1})")
                return@withContext RemoteResult.Success(result, latency)
            }
            if (attempt < MAX_RETRIES) {
                Log.w(TAG, "Remote attempt ${attempt + 1} failed type=$lastError; retrying in ${RETRY_DELAY_MS}ms")
                delay(RETRY_DELAY_MS)
            }
        }
        LoggingService.error(TAG, "Remote model failed after $MAX_RETRIES retries type=$lastError")
        RemoteResult.Failure("Remote request failed")
    }

    suspend fun generateStream(
        model: RemoteModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 512,
        temperature: Float = 0.8f,
        onToken: suspend (String) -> Unit
    ): RemoteResult = withContext(Dispatchers.IO) {
        if (!LocalEndpointPolicy.isAllowed(model.serverUrl)) {
            return@withContext RemoteResult.Failure("Endpoint rejected by local security policy")
        }
        val startMs = System.currentTimeMillis()
        var result: RemoteResult = RemoteResult.Failure("Remote streaming failed")
        // Report 1, item 14 (stage 27): retry the stream like generate() does, but only
        // while nothing has reached the caller. After the first token a retry would
        // duplicate text, so a mid-stream failure is returned as-is.
        for (attempt in 0..MAX_RETRIES) {
            ensureActive()
            var tokensDelivered = false
            result = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    executeStreamingRequest(model, prompt, systemPrompt, maxTokens, temperature) { token ->
                        tokensDelivered = true
                        onToken(token)
                    }
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    // A watcher-triggered disconnect after the timeout fired surfaces as an IOException.
                    currentCoroutineContext().ensureActive()
                    RemoteResult.Failure(e.message ?: "Remote streaming failed")
                }
            } ?: RemoteResult.Failure("Remote model timed out after ${TIMEOUT_MS / 1000}s")
            val failure = result as? RemoteResult.Failure ?: break
            if (!shouldRetryStream(failure.error, tokensDelivered) || attempt >= MAX_RETRIES) break
            Log.w(TAG, "Remote stream attempt ${attempt + 1} failed before first token; retrying in ${RETRY_DELAY_MS}ms")
            delay(RETRY_DELAY_MS)
        }
        if (result is RemoteResult.Success) {
            LoggingService.debug(TAG, "Remote stream completed in ${System.currentTimeMillis() - startMs}ms")
        }
        return@withContext result
    }


    suspend fun testConnection(model: RemoteModel, timeoutMs: Long = 10_000L): Boolean = withContext(Dispatchers.IO) {
        if (!LocalEndpointPolicy.isAllowed(model.serverUrl)) return@withContext false
        val socketTimeoutMs = timeoutMs.coerceIn(1L, 8_000L).toInt()
        withTimeoutOrNull(timeoutMs.coerceAtLeast(1L)) {
            runCatching {
                val url  = URL(normalizeUrl(model.serverUrl) + "/v1/models")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = socketTimeoutMs
                    conn.readTimeout    = socketTimeoutMs
                    conn.requestMethod  = "GET"
                    if (model.apiKey.isNotBlank()) {
                        conn.setRequestProperty("Authorization", "Bearer ${model.apiKey}")
                    }
                    conn.connect()
                    val code = conn.responseCode
                    code in 200..299
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { false }
        } ?: false
    }

    private suspend fun executeRequest(
        model: RemoteModel,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val conn = (URL("${normalizeUrl(model.serverUrl)}/v1/chat/completions")
            .openConnection() as HttpURLConnection)
        activeConnection = conn
        val watcher = watchConnectionForCancellation(conn)
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (model.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            }

            val messages = buildMessagesJson(systemPrompt, prompt)
            val body = "{\"model\":${jsonString(model.name)},\"messages\":$messages,\"max_tokens\":$maxTokens,\"temperature\":$temperature,\"stream\":false}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }

            return parseCompletionText(conn.inputStream.bufferedReader().readText())
        } finally {
            watcher.cancel()
            activeConnection = null
            conn.disconnect()
        }
    }

    private suspend fun executeStreamingRequest(
        model: RemoteModel,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float,
        onToken: suspend (String) -> Unit
    ): RemoteResult {
        val startMs  = System.currentTimeMillis()
        val conn     = (URL("${normalizeUrl(model.serverUrl)}/v1/chat/completions")
            .openConnection() as HttpURLConnection)
        activeConnection = conn
        val watcher = watchConnectionForCancellation(conn)
        val fullResponse = StringBuilder()
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (model.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            }

            val messages = buildMessagesJson(systemPrompt, prompt)
            val body = "{\"model\":${jsonString(model.name)},\"messages\":$messages,\"max_tokens\":$maxTokens,\"temperature\":$temperature,\"stream\":true}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    currentCoroutineContext().ensureActive()
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val token = parseDeltaText(payload)
                    if (token.isNotEmpty()) {
                        fullResponse.append(token)
                        onToken(token)
                    }
                }
            }
            return RemoteResult.Success(fullResponse.toString(), System.currentTimeMillis() - startMs)
        } finally {
            watcher.cancel()
            activeConnection = null
            try { conn.disconnect() } catch (_: Exception) {}
        }
    }

    // ── JSON builders ─────────────────────────────────────────────────────────

    private fun buildMessagesJson(systemPrompt: String, userPrompt: String): String = buildString {
        append("[")
        if (systemPrompt.isNotBlank()) {
            append("{\"role\":\"system\",\"content\":${jsonString(systemPrompt)}},")
        }
        append("{\"role\":\"user\",\"content\":${jsonString(userPrompt)}}")
        append("]")
    }

    // ── Parsers ───────────────────────────────────────────────────────────────

    private fun parseCompletionText(json: String): String {
        val choicesStart = json.indexOf("\"choices\"")
        if (choicesStart < 0) return json
        val contentStart = json.indexOf("\"content\"", choicesStart)
        if (contentStart < 0) return json
        val colonIdx = json.indexOf(":", contentStart)
        if (colonIdx < 0) return json
        val valueStart = json.indexOf("\"", colonIdx + 1)
        if (valueStart < 0) return json
        val valueEnd = findStringEnd(json, valueStart + 1)
        if (valueEnd < 0) return json
        return json.substring(valueStart + 1, valueEnd)
            .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\").trim()
    }

    private fun parseDeltaText(json: String): String {
        val deltaStart   = json.indexOf("\"delta\"")
        val contentStart = if (deltaStart >= 0) json.indexOf("\"content\"", deltaStart)
                          else json.indexOf("\"content\"")
        if (contentStart < 0) return ""
        val colonIdx = json.indexOf(":", contentStart)
        if (colonIdx < 0) return ""
        val afterColon = json.substring(colonIdx + 1).trimStart()
        if (!afterColon.startsWith("\"")) return ""
        val e = findStringEnd(afterColon, 1)
        if (e < 0) return ""
        return afterColon.substring(1, e)
            .replace("\\n", "\n").replace("\\\"", "\"").replace("\\\\", "\\")
    }

    private fun findStringEnd(s: String, start: Int): Int {
        var i = start
        while (i < s.length) {
            when { s[i] == '\\' -> i += 2; s[i] == '"' -> return i; else -> i++ }
        }
        return -1
    }

    /**
     * Normalise a server URL for use as a base path.
     *
     * Strips trailing slash. If the URL already ends with "/v1", that suffix
     * is removed because every call site appends "/v1/chat/completions" or
     * "/v1/models" — we must never produce "…/v1/v1/…".
     *
     * Examples:
     *  "https://api.groq.com/openai/v1"  → "https://api.groq.com/openai"
     *  "http://localhost:11434/v1"        → "http://localhost:11434"
     *  "http://localhost:11434"           → "http://localhost:11434"
     *  "https://openrouter.ai/api"        → "https://openrouter.ai/api"
     */
    private fun normalizeUrl(url: String): String {
        val trimmed = url.trimEnd('/')
        return if (trimmed.endsWith("/v1")) trimmed.dropLast(3).trimEnd('/') else trimmed
    }

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
