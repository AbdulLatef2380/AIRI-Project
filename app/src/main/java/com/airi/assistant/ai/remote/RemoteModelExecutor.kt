package com.airi.assistant.ai.remote

import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

class RemoteModelExecutor {

    companion object {
        private const val TAG = "RemoteModelExecutor"
        private const val TIMEOUT_MS = 15_000L
        private const val CONNECT_TIMEOUT = 5_000
        private const val READ_TIMEOUT    = 15_000
        private const val MAX_RETRIES     = 1
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
        repeat(MAX_RETRIES + 1) { attempt ->
            val result = withTimeoutOrNull(TIMEOUT_MS) {
                runCatching {
                    executeRequest(model, prompt, systemPrompt, maxTokens, temperature)
                }.getOrElse { e ->
                    lastError = e.message ?: "Request failed"
                    null
                }
            }
            if (result != null) {
                val latency = System.currentTimeMillis() - startMs
                LoggingService.debug(TAG, "Remote response in ${latency}ms (attempt ${attempt + 1})")
                return@withContext RemoteResult.Success(result, latency)
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
                val url = URL(normalizeUrl(model.serverUrl) + "/v1/models")
                val conn = url.openConnection() as HttpURLConnection
                conn.connectTimeout = 8_000
                conn.readTimeout    = 8_000
                conn.requestMethod  = "GET"
                if (model.apiKey.isNotBlank()) {
                    conn.setRequestProperty("Authorization", "Bearer ${model.apiKey}")
                }
                conn.connect()
                val code = conn.responseCode
                conn.disconnect()
                code in 200..299 || code == 401
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
        val base    = normalizeUrl(model.serverUrl)
        val endpoint = "$base/v1/chat/completions"
        val url     = URL(endpoint)
        val conn    = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout    = READ_TIMEOUT
            conn.doOutput       = true
            conn.setRequestProperty("Content-Type", "application/json")
            if (model.apiKey.isNotBlank()) {
                conn.setRequestProperty("Authorization", "Bearer ${model.apiKey}")
            }

            val messages = buildString {
                append("[")
                if (systemPrompt.isNotBlank()) {
                    append("{\"role\":\"system\",\"content\":${jsonString(systemPrompt)}},")
                }
                append("{\"role\":\"user\",\"content\":${jsonString(prompt)}}")
                append("]")
            }
            val body = "{\"messages\":$messages,\"max_tokens\":$maxTokens,\"temperature\":$temperature,\"stream\":false}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }

            val responseJson = conn.inputStream.bufferedReader().readText()
            return parseCompletionText(responseJson)
        } finally {
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
        val startMs = System.currentTimeMillis()
        val base    = normalizeUrl(model.serverUrl)
        val endpoint = "$base/v1/chat/completions"
        val conn    = (URL(endpoint).openConnection() as HttpURLConnection)
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

            val messages = buildString {
                append("[")
                if (systemPrompt.isNotBlank()) {
                    append("{\"role\":\"system\",\"content\":${jsonString(systemPrompt)}},")
                }
                append("{\"role\":\"user\",\"content\":${jsonString(prompt)}}")
                append("]")
            }
            val body = "{\"messages\":$messages,\"max_tokens\":$maxTokens,\"temperature\":$temperature,\"stream\":true}"
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.readText() ?: "HTTP $code"
                throw RuntimeException("HTTP $code: $err")
            }

            BufferedReader(InputStreamReader(conn.inputStream)).use { reader ->
                while (true) {
                    val raw = reader.readLine() ?: break
                    val line = raw.trim()
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val token = parseDeltaText(payload)
                    if (token.isNotEmpty()) {
                        fullResponse.append(token)
                        onToken(token)
                        delay(0)
                    }
                }
            }
            return RemoteResult.Success(fullResponse.toString(), System.currentTimeMillis() - startMs)
        } finally {
            conn.disconnect()
        }
    }

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
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .trim()
    }

    private fun parseDeltaText(json: String): String {
        val deltaStart = json.indexOf("\"delta\"")
        val contentStart = if (deltaStart >= 0) json.indexOf("\"content\"", deltaStart) else json.indexOf("\"content\"")
        if (contentStart < 0) return ""
        val colonIdx = json.indexOf(":", contentStart)
        if (colonIdx < 0) return ""
        val valueStart = json.indexOf("\"", colonIdx + 1)
        if (valueStart < 0) return ""
        val valueEnd = findStringEnd(json, valueStart + 1)
        if (valueEnd < 0) return ""
        return json.substring(valueStart + 1, valueEnd)
            .replace("\\n", "\n")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
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

    private fun normalizeUrl(url: String): String =
        url.trimEnd('/')

    private fun jsonString(s: String): String =
        "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""
}
