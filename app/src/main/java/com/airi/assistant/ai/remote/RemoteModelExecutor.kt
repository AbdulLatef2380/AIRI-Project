package com.airi.assistant.ai.remote

import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
 *  - [ensureActive] checked in the stream loop — coroutine cancellation is
 *    honoured within one token's processing time.
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
        val startMs = System.currentTimeMillis()
        var lastError = "Unknown error"
        for (attempt in 0..MAX_RETRIES) {
            ensureActive()
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    executeRequest(model, prompt, systemPrompt, maxTokens, temperature)
                }.getOrElse { e ->
                    if (e is CancellationException) throw e
                    lastError = e.message ?: "Request failed"
                    null
                }
            }
            if (result != null) {
                val latency = System.currentTimeMillis() - startMs
                LoggingService.debug(TAG, "Remote response in ${latency}ms (attempt ${attempt + 1})")
                return@withContext RemoteResult.Success(result, latency)
            }
            if (attempt < MAX_RETRIES) {
                Log.w(TAG, "Attempt ${attempt + 1} failed, retrying in ${RETRY_DELAY_MS}ms: $lastError")
                delay(RETRY_DELAY_MS)
            }
        }
        LoggingService.error(TAG, "Remote model failed after $MAX_RETRIES retries: $lastError")
        RemoteResult.Failure(lastError)
    }

    suspend fun generateStream(
        model: RemoteModel,
        prompt: String,
        systemPrompt: String = "",
        maxTokens: Int = 512,
        temperature: Float = 0.8f,
        onToken: suspend (String) -> Unit
    ): RemoteResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val result = withTimeoutOrNull(TIMEOUT_MS) {
            runCatching {
                executeStreamingRequest(model, prompt, systemPrompt, maxTokens, temperature, onToken)
            }.getOrElse { e ->
                if (e is CancellationException) throw e
                RemoteResult.Failure(e.message ?: "Remote streaming failed")
            }
        } ?: RemoteResult.Failure("Remote model timed out after ${TIMEOUT_MS / 1000}s")
        if (result is RemoteResult.Success) {
            LoggingService.debug(TAG, "Remote stream completed in ${System.currentTimeMillis() - startMs}ms")
        }
        result
    }

    suspend fun testConnection(model: RemoteModel): Boolean = withContext(Dispatchers.IO) {
        withTimeoutOrNull(10_000L) {
            runCatching {
                val url  = URL(normalizeUrl(model.serverUrl) + "/v1/models")
                val conn = url.openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 8_000
                    conn.readTimeout    = 8_000
                    conn.requestMethod  = "GET"
                    if (model.apiKey.isNotBlank()) {
                        conn.setRequestProperty("Authorization", "Bearer ${model.apiKey}")
                    }
                    conn.connect()
                    val code = conn.responseCode
                    code in 200..299 || code == 401
                } finally {
                    conn.disconnect()
                }
            }.getOrElse { false }
        } ?: false
    }

    private fun executeRequest(
        model: RemoteModel,
        prompt: String,
        systemPrompt: String,
        maxTokens: Int,
        temperature: Float
    ): String {
        val conn = (URL("${normalizeUrl(model.serverUrl)}/v1/chat/completions")
            .openConnection() as HttpURLConnection)
        activeConnection = conn
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
            val body = "{\"messages\":$messages,\"max_tokens\":$maxTokens,\"temperature\":$temperature,\"stream\":false}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }

            return parseCompletionText(conn.inputStream.bufferedReader().readText())
        } finally {
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
            val body = "{\"messages\":$messages,\"max_tokens\":$maxTokens,\"temperature\":$temperature,\"stream\":true}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                while (true) {
                    ensureActive()   // Cooperative cancellation — responds within one line
                    val raw = reader.readLine() ?: break
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

    private fun normalizeUrl(url: String): String = url.trimEnd('/')

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
