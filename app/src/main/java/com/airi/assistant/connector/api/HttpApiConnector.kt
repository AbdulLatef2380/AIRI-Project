package com.airi.assistant.connector.api

import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HttpApiConnector — generic HTTP client for external REST APIs.
 *
 * The agent uses this to call any REST endpoint not covered by a dedicated
 * connector. All calls use a shared [OkHttpClient] with conservative timeouts
 * so a single slow endpoint cannot block the agent indefinitely.
 *
 * ## Supported actions
 * | action   | text param   | required params          | notes                        |
 * |----------|--------------|--------------------------|------------------------------|
 * | `get`    | —            | `url`                    | Optional `headers.*` params  |
 * | `post`   | JSON body    | `url`                    | Content-Type: application/json|
 * | `put`    | JSON body    | `url`                    | Content-Type: application/json|
 * | `delete` | —            | `url`                    |                              |
 * | `patch`  | JSON body    | `url`                    | Content-Type: application/json|
 *
 * ## Headers
 * Any [ConnectorInput.params] entry whose key starts with `header.` is
 * forwarded as an HTTP header (the prefix is stripped). Example:
 *   params = mapOf("header.Authorization" to "Bearer sk-…")
 * This lets the agent authenticate without baking credentials into the
 * connector itself.
 *
 * ## Response truncation
 * Responses longer than [MAX_BODY_BYTES] are truncated with a clear suffix
 * rather than OOM-ing on a runaway server.
 */
class HttpApiConnector : Connector {

    override val id          = "http_api"
    override val name        = "HTTP API"
    override val description = "Call any REST endpoint via GET, POST, PUT, DELETE, or PATCH."
    override val type        = ConnectorType.API

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("http", "rest", "api", "web", "get", "post"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        _state.value = ConnectorState(
            connected = true, healthy = true,
            statusLine = "HTTP client ready (${CONNECT_TIMEOUT_S}s connect / ${READ_TIMEOUT_S}s read)",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* stateless */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val url = input.params["url"].orEmpty()
        if (url.isBlank()) {
            return@withContext ConnectorOutput.Failure(code = "bad_input", message = "Missing 'url' param")
        }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ConnectorOutput.Failure(code = "bad_input", message = "URL must start with http:// or https://")
        }

        val headers = input.params.filterKeys { it.startsWith("header.") }
            .mapKeys { (k, _) -> k.removePrefix("header.") }

        when (input.action) {
            "get"    -> httpCall("GET",    url, null, headers)
            "post"   -> httpCall("POST",   url, input.text, headers)
            "put"    -> httpCall("PUT",    url, input.text, headers)
            "delete" -> httpCall("DELETE", url, null, headers)
            "patch"  -> httpCall("PATCH",  url, input.text, headers)
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "HttpApiConnector: unknown action '${input.action}'",
            )
        }
    }

    private fun httpCall(
        method: String,
        url: String,
        body: String?,
        headers: Map<String, String>,
    ): ConnectorOutput {
        val start = System.currentTimeMillis()
        val reqBody = when {
            body != null -> body.toRequestBody(JSON_MEDIA_TYPE)
            method == "POST" || method == "PUT" || method == "PATCH" ->
                "{}".toRequestBody(JSON_MEDIA_TYPE)
            else -> null
        }
        val reqBuilder = Request.Builder().url(url).method(method, reqBody)
        headers.forEach { (k, v) -> reqBuilder.addHeader(k, v) }

        return runCatching {
            val response = client.newCall(reqBuilder.build()).execute()
            val statusCode = response.code
            val rawBody = response.body?.bytes() ?: ByteArray(0)
            val truncated = rawBody.size > MAX_BODY_BYTES
            val bodyText = if (truncated) {
                String(rawBody, 0, MAX_BODY_BYTES, Charsets.UTF_8) +
                    "\n[truncated — response exceeded ${MAX_BODY_BYTES / 1024} KB]"
            } else {
                String(rawBody, Charsets.UTF_8)
            }
            val elapsed = System.currentTimeMillis() - start
            Log.i("AIRI_PROOF", "HTTP_CALL method=$method url=${url.take(80)} status=$statusCode elapsed=${elapsed}ms truncated=$truncated")

            if (response.isSuccessful) {
                ConnectorOutput.Success(
                    text = bodyText,
                    data = mapOf(
                        "status_code" to statusCode.toString(),
                        "elapsed_ms"  to elapsed.toString(),
                        "truncated"   to truncated.toString(),
                    ),
                    durationMs = elapsed,
                )
            } else {
                ConnectorOutput.Failure(
                    code = "http_${statusCode}",
                    message = "HTTP $statusCode from $url: ${bodyText.take(300)}",
                    retryable = statusCode in listOf(429, 500, 502, 503, 504),
                )
            }
        }.getOrElse { t ->
            val elapsed = System.currentTimeMillis() - start
            Log.w("AIRI_PROOF", "HTTP_CALL_FAILED method=$method url=${url.take(80)} elapsed=${elapsed}ms cause=${t.message}")
            ConnectorOutput.Failure(
                code = "network_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val CONNECT_TIMEOUT_S = 10L
        private const val READ_TIMEOUT_S    = 30L
        private const val WRITE_TIMEOUT_S   = 15L
        private const val MAX_BODY_BYTES    = 256 * 1024
    }
}
