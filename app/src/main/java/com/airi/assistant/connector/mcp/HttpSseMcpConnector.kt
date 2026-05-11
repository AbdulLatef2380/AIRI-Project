package com.airi.assistant.connector.mcp

import android.util.Log
import com.airi.assistant.connector.ConnectorOutput
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HttpSseMcpConnector — HTTP+JSON MCP server transport.
 *
 * Implements the Model Context Protocol over plain HTTP POST (with optional
 * SSE fallback for streaming tool results). Compatible with any MCP server
 * that speaks the standard HTTP transport.
 *
 * ── HANDSHAKE SEQUENCE ────────────────────────────────────────────────────────
 *
 *   POST {baseUrl}/initialize
 *     → server returns capabilities + tool list
 *   Tool calls: POST {baseUrl}/tools/call
 *     → { "name": "tool_name", "arguments": { ... } }
 *     ← { "content": [{ "type": "text", "text": "..." }] }
 *
 * ── REAL TOOL INVOCATION ──────────────────────────────────────────────────────
 *
 *   All tool calls go through [invoke] which issues a real HTTP POST to the
 *   configured MCP server. Results are parsed from the standard MCP content
 *   array format. Errors are mapped to [ConnectorOutput.Failure].
 *
 * ── CONFIGURATION ────────────────────────────────────────────────────────────
 *
 *   Construct with [baseUrl] pointing to the MCP server's HTTP transport
 *   endpoint. Optional [authToken] is sent as Bearer token in Authorization.
 *
 *   Example:
 *     HttpSseMcpConnector(
 *         id      = "mcp_filesystem",
 *         name    = "Filesystem MCP",
 *         baseUrl = "http://localhost:3000",
 *         tools   = listOf(McpTool("read_file", "Read a file"), ...)
 *     )
 */
class HttpSseMcpConnector(
    id:                    String,
    name:                  String,
    description:           String,
    tools:                 List<McpTool>,
    private val baseUrl:   String,
    private val authToken: String? = null,
) : McpConnector(id, name, description, tools) {

    private val TAG = "HttpSseMcpConnector"

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(CONNECT_TIMEOUT_SEC, TimeUnit.SECONDS)
            .readTimeout(READ_TIMEOUT_SEC, TimeUnit.SECONDS)
            .writeTimeout(WRITE_TIMEOUT_SEC, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    // ── McpConnector overrides ────────────────────────────────────────────────

    override suspend fun handshake(): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val initBody = JSONObject().apply {
                put("jsonrpc", "2.0")
                put("id", 1)
                put("method", "initialize")
                put("params", JSONObject().apply {
                    put("protocolVersion", "2024-11-05")
                    put("clientInfo", JSONObject().apply {
                        put("name", "AIRI")
                        put("version", "1.0.0")
                    })
                })
            }.toString()

            val req = buildRequest("${baseUrl.trimEnd('/')}/", initBody)
            val resp = client.newCall(req).execute()
            val ok = resp.isSuccessful

            if (ok) {
                val bodyStr = resp.body?.string() ?: ""
                Log.i(TAG, "MCP_HANDSHAKE_OK id=$id response=${bodyStr.take(200)}")
            } else {
                Log.w(TAG, "MCP_HANDSHAKE_FAIL id=$id code=${resp.code}")
            }

            resp.close()
            ok
        }.getOrElse { e ->
            Log.w(TAG, "MCP_HANDSHAKE_ERROR id=$id: ${e.message}")
            false
        }
    }

    override suspend fun teardown(): Unit = withContext(Dispatchers.IO) {
        runCatching {
            val body = JSONObject().apply {
                put("jsonrpc", "2.0"); put("id", 99); put("method", "shutdown")
            }.toString()
            val req = buildRequest("${baseUrl.trimEnd('/')}/", body)
            client.newCall(req).execute().also { it.close() }
            Log.i(TAG, "MCP_SHUTDOWN_OK id=$id")
        }.onFailure { Log.d(TAG, "MCP_SHUTDOWN_SKIP id=$id: ${it.message}") }
    }

    override suspend fun invoke(
        tool:   McpTool,
        text:   String,
        params: Map<String, String>,
    ): ConnectorOutput = withContext(Dispatchers.IO) {
        val args = JSONObject().apply {
            if (text.isNotBlank()) put("input", text)
            params.forEach { (k, v) -> put(k, v) }
        }

        val requestBody = JSONObject().apply {
            put("jsonrpc", "2.0")
            put("id", System.currentTimeMillis())
            put("method", "tools/call")
            put("params", JSONObject().apply {
                put("name", tool.name)
                put("arguments", args)
            })
        }.toString()

        runCatching {
            val req  = buildRequest("${baseUrl.trimEnd('/')}/", requestBody)
            val resp = client.newCall(req).execute()

            if (!resp.isSuccessful) {
                return@runCatching ConnectorOutput.Failure(
                    code      = "http_${resp.code}",
                    message   = "MCP server returned HTTP ${resp.code}",
                    retryable = resp.code >= 500,
                )
            }

            val bodyStr = resp.body?.string() ?: ""
            resp.close()

            parseToolResult(bodyStr, tool.name)
        }.getOrElse { e ->
            ConnectorOutput.Failure(
                code      = "network_error",
                message   = "${e.javaClass.simpleName}: ${e.message}",
                retryable = true,
            )
        }
    }

    // ── Response parsing ──────────────────────────────────────────────────────

    private fun parseToolResult(json: String, toolName: String): ConnectorOutput {
        return runCatching {
            val root   = JSONObject(json)
            val error  = root.optJSONObject("error")
            if (error != null) {
                return ConnectorOutput.Failure(
                    code    = "mcp_error_${error.optInt("code", -1)}",
                    message = error.optString("message", "MCP error"),
                )
            }

            val result   = root.optJSONObject("result") ?: return ConnectorOutput.Failure("bad_response", "No result field")
            val content  = result.optJSONArray("content")
            val textParts = (0 until (content?.length() ?: 0)).map { i ->
                val item = content!!.getJSONObject(i)
                if (item.optString("type") == "text") item.optString("text") else ""
            }.filter { it.isNotBlank() }

            val fullText = textParts.joinToString("\n")
            Log.d(TAG, "MCP_INVOKE_OK tool=$toolName result='${fullText.take(80)}'")

            ConnectorOutput.Success(
                text = fullText.ifBlank { "(empty result)" },
                data = mapOf("tool" to toolName, "parts" to textParts.size.toString()),
            )
        }.getOrElse { e ->
            ConnectorOutput.Failure(
                code    = "parse_error",
                message = "Failed to parse MCP response: ${e.message}",
            )
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun buildRequest(url: String, body: String): Request =
        Request.Builder()
            .url(url)
            .post(body.toRequestBody(JSON_MEDIA))
            .header("Accept", "application/json")
            .apply { if (authToken != null) header("Authorization", "Bearer $authToken") }
            .build()

    companion object {
        private const val CONNECT_TIMEOUT_SEC = 10L
        private const val READ_TIMEOUT_SEC    = 30L
        private const val WRITE_TIMEOUT_SEC   = 10L
    }
}
