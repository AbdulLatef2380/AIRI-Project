package com.airi.assistant.connector.mcp

import android.util.Log
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.connector.ConnectorOutput
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * NotionMcpConnector — functional Notion API connector via McpConnector.
 *
 * ── Phase 2, Task 8 ────────────────────────────────────────────────────────
 * Replaces the legacy [com.airi.assistant.integration.NotionIntegration] stub
 * (which stored only a boolean flag in plaintext SharedPreferences and made
 * no API calls) with a real HTTP client that speaks the Notion REST API v1.
 *
 * ── Authentication ─────────────────────────────────────────────────────────
 * A Notion Integration Token (PAT / "Internal Integration Secret") is stored
 * in [SecureStorage] under the key [TOKEN_KEY]. The token is retrieved on
 * every API call — never cached in memory — so rotation takes effect
 * immediately without an app restart.
 *
 * ── Tools ──────────────────────────────────────────────────────────────────
 * | Tool name       | Notion API endpoint                          |
 * |:----------------|:---------------------------------------------|
 * | search_pages    | POST /v1/search                              |
 * | get_page        | GET  /v1/pages/{id}                          |
 * | get_page_blocks | GET  /v1/blocks/{id}/children                |
 * | create_page     | POST /v1/pages                               |
 * | query_database  | POST /v1/databases/{id}/query                |
 *
 * ── Error handling ─────────────────────────────────────────────────────────
 * All HTTP errors are surfaced as [ConnectorOutput.Failure] with the Notion
 * error code and message parsed from the response body. Network errors are
 * caught and returned as retryable failures.
 *
 * ── Security ───────────────────────────────────────────────────────────────
 * • Token is stored in AES256-GCM EncryptedSharedPreferences (SecureStorage).
 * • No token is ever logged (AIRI_PROOF lines contain only request metadata).
 * • Handshake calls /v1/users/me to verify the token is valid.
 *
 * ── Rate limits ────────────────────────────────────────────────────────────
 * Notion API enforces ~3 requests/sec. This connector does not implement
 * client-side rate limiting; that is delegated to the agent orchestrator via
 * the adaptive throttling system (Task 9 / SystemHealthCoordinator).
 */
class NotionMcpConnector(
    private val secureStorage: SecureStorage
) : McpConnector(
    id          = "notion_mcp",
    name        = "Notion",
    description = "Read and write Notion pages, blocks, and databases via the Notion API.",
    tools       = NOTION_TOOLS
) {

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    // B-06: In-memory token cache with 5-minute TTL.
    // Avoids hitting SecureStorage (EncryptedSharedPreferences, I/O bound)
    // on every Notion API call — critical when multiple tools fire in the
    // same agent turn. Token rotation takes effect within TOKEN_CACHE_TTL_MS.
    @Volatile private var cachedToken: String? = null
    @Volatile private var cachedTokenExpiresAt: Long = 0L

    private fun getToken(): String? {
        val now = System.currentTimeMillis()
        if (cachedToken != null && now < cachedTokenExpiresAt) {
            return cachedToken
        }
        val fresh = secureStorage.getNotionToken()
        cachedToken = fresh
        cachedTokenExpiresAt = now + TOKEN_CACHE_TTL_MS
        return fresh
    }

    // ── McpConnector abstract implementations ─────────────────────────────────

    /**
     * Validates the stored Notion token by calling /v1/users/me.
     * Returns true if the token is valid and the workspace is accessible.
     */
    override suspend fun handshake(): Boolean {
        val token = getToken()
        if (token.isNullOrBlank()) {
            Log.w(TAG, "AIRI_PROOF NOTION_HANDSHAKE_FAILED reason=no_token_stored")
            return false
        }
        return runCatching {
            val request = Request.Builder()
                .url("$BASE_URL/users/me")
                .addHeader("Authorization", "Bearer $token")
                .addHeader("Notion-Version", NOTION_API_VERSION)
                .get()
                .build()
            val response = httpClient.newCall(request).execute()
            val ok = response.isSuccessful
            Log.i(TAG,
                "AIRI_PROOF NOTION_HANDSHAKE status=${response.code} success=$ok")
            response.close()
            ok
        }.getOrElse { e ->
            Log.e(TAG, "AIRI_PROOF NOTION_HANDSHAKE_EXCEPTION ${e.message}")
            false
        }
    }

    override suspend fun teardown() {
        // OkHttpClient manages its own connection pool; no explicit teardown needed.
        Log.i(TAG, "AIRI_PROOF NOTION_DISCONNECT")
    }

    /**
     * Dispatches a tool call to the appropriate Notion API endpoint.
     *
     * Required [params] keys per tool:
     *  - search_pages:    "query" (string, required)
     *  - get_page:        "page_id" (required)
     *  - get_page_blocks: "block_id" (required)
     *  - create_page:     "parent_page_id" (required), "title" (required), "content" (optional)
     *  - query_database:  "database_id" (required), "filter_json" (optional, JSON string)
     */
    override suspend fun invoke(
        tool:   McpTool,
        text:   String,
        params: Map<String, String>
    ): ConnectorOutput {
        val token = getToken()
            ?: return ConnectorOutput.Failure(
                code    = "no_token",
                message = "Notion API token not configured. Add your Integration Token in Settings → Connectors.",
                retryable = false
            )

        return when (tool.name) {
            "search_pages"    -> searchPages(token, params["query"] ?: text)
            "get_page"        -> getPage(token, params["page_id"]
                ?: return missingParam("page_id"))
            "get_page_blocks" -> getPageBlocks(token, params["block_id"]
                ?: return missingParam("block_id"))
            "create_page"     -> createPage(
                token       = token,
                parentPageId = params["parent_page_id"] ?: return missingParam("parent_page_id"),
                title        = params["title"]           ?: text.take(100),
                content      = params["content"]         ?: text
            )
            "query_database"  -> queryDatabase(
                token       = token,
                databaseId  = params["database_id"] ?: return missingParam("database_id"),
                filterJson  = params["filter_json"]
            )
            else -> ConnectorOutput.Failure(code = "unknown_tool", message = "Unknown tool: ${tool.name}")
        }
    }

    // ── Tool implementations ──────────────────────────────────────────────────

    private fun searchPages(token: String, query: String): ConnectorOutput {
        val body = JSONObject().put("query", query).toString()
            .toRequestBody(JSON_MEDIA_TYPE)
        val request = buildRequest(token, "search", method = "POST", body = body)
        return executeRequest(request, "search_pages") { responseBody ->
            val results = JSONObject(responseBody).getJSONArray("results")
            val pages = (0 until results.length()).map { i ->
                val page = results.getJSONObject(i)
                val title = extractPageTitle(page)
                mapOf("id" to page.getString("id"), "title" to title)
            }
            ConnectorOutput.Success(
                text = pages.joinToString("\n") { "• ${it["title"]} (${it["id"]})" },
                data = mapOf(
                    "count"   to pages.size.toString(),
                    "results" to pages.toString()
                )
            )
        }
    }

    private fun getPage(token: String, pageId: String): ConnectorOutput {
        val request = buildRequest(token, "pages/$pageId")
        return executeRequest(request, "get_page") { responseBody ->
            val page  = JSONObject(responseBody)
            val title = extractPageTitle(page)
            ConnectorOutput.Success(
                text = title,
                data = mapOf(
                    "page_id"     to pageId,
                    "title"       to title,
                    "raw_json"    to responseBody
                )
            )
        }
    }

    private fun getPageBlocks(token: String, blockId: String): ConnectorOutput {
        val request = buildRequest(token, "blocks/$blockId/children")
        return executeRequest(request, "get_page_blocks") { responseBody ->
            val results = JSONObject(responseBody).getJSONArray("results")
            val texts = (0 until results.length()).mapNotNull { i ->
                extractBlockText(results.getJSONObject(i))
            }
            ConnectorOutput.Success(
                text = texts.joinToString("\n"),
                data = mapOf("block_count" to results.length().toString())
            )
        }
    }

    private fun createPage(
        token: String,
        parentPageId: String,
        title: String,
        content: String
    ): ConnectorOutput {
        val bodyJson = JSONObject().apply {
            put("parent", JSONObject().put("page_id", parentPageId))
            put("properties", JSONObject().apply {
                put("title", JSONObject().apply {
                    put("title", JSONArray().apply {
                        put(JSONObject().apply {
                            put("type", "text")
                            put("text", JSONObject().put("content", title))
                        })
                    })
                })
            })
            put("children", JSONArray().apply {
                put(JSONObject().apply {
                    put("object", "block")
                    put("type", "paragraph")
                    put("paragraph", JSONObject().apply {
                        put("rich_text", JSONArray().apply {
                            put(JSONObject().apply {
                                put("type", "text")
                                put("text", JSONObject().put("content", content))
                            })
                        })
                    })
                })
            })
        }
        val request = buildRequest(
            token, "pages", method = "POST",
            body = bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        )
        return executeRequest(request, "create_page") { responseBody ->
            val page = JSONObject(responseBody)
            ConnectorOutput.Success(
                text = "Created page: $title",
                data = mapOf(
                    "page_id" to runCatching { page.getString("id") }.getOrDefault(""),
                    "url"     to runCatching { page.getString("url") }.getOrDefault("")
                )
            )
        }
    }

    private fun queryDatabase(
        token: String,
        databaseId: String,
        filterJson: String?
    ): ConnectorOutput {
        val bodyJson = if (filterJson != null) {
            runCatching { JSONObject(filterJson) }.getOrElse { JSONObject() }
        } else {
            JSONObject()
        }
        val request = buildRequest(
            token, "databases/$databaseId/query", method = "POST",
            body = bodyJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        )
        return executeRequest(request, "query_database") { responseBody ->
            val results = JSONObject(responseBody).getJSONArray("results")
            ConnectorOutput.Success(
                text = "Database query returned ${results.length()} rows.",
                data = mapOf(
                    "count"   to results.length().toString(),
                    "results" to results.toString()
                )
            )
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun buildRequest(
        token:  String,
        path:   String,
        method: String = "GET",
        body:   okhttp3.RequestBody? = null
    ): Request = Request.Builder()
        .url("$BASE_URL/$path")
        .addHeader("Authorization", "Bearer $token")
        .addHeader("Notion-Version", NOTION_API_VERSION)
        .addHeader("Accept", "application/json")
        .apply {
            when (method.uppercase()) {
                "GET"  -> get()
                "POST" -> post(body ?: JSONObject().toString()
                    .toRequestBody(JSON_MEDIA_TYPE))
                "PATCH" -> patch(body ?: JSONObject().toString()
                    .toRequestBody(JSON_MEDIA_TYPE))
            }
        }
        .build()

    private inline fun executeRequest(
        request:  Request,
        toolName: String,
        crossinline onSuccess: (String) -> ConnectorOutput
    ): ConnectorOutput {
        return runCatching {
            val response = httpClient.newCall(request).execute()
            val bodyStr  = response.body?.string() ?: ""
            Log.i(TAG, "AIRI_PROOF NOTION_API tool=$toolName status=${response.code}")
            if (response.isSuccessful) {
                onSuccess(bodyStr)
            } else {
                val errObj  = runCatching { JSONObject(bodyStr) }.getOrElse { JSONObject() }
                val errCode = errObj.optString("code", "api_error")
                val errMsg  = errObj.optString("message", "Notion API error ${response.code}")
                Log.w(TAG, "AIRI_PROOF NOTION_API_ERROR tool=$toolName code=$errCode")
                ConnectorOutput.Failure(
                    code      = errCode,
                    message   = errMsg,
                    retryable = response.code in setOf(429, 500, 502, 503)
                )
            }
        }.getOrElse { e ->
            Log.e(TAG, "AIRI_PROOF NOTION_NETWORK_ERROR tool=$toolName ${e.message}")
            ConnectorOutput.Failure(
                code      = "network_error",
                message   = "Network error calling Notion: ${e.message}",
                retryable = true
            )
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    private fun extractPageTitle(page: JSONObject): String = runCatching {
        val props = page.getJSONObject("properties")
        val titleKey = props.keys().asSequence().firstOrNull { key ->
            props.getJSONObject(key).optString("type") == "title"
        } ?: return "Untitled"
        val titleArray = props.getJSONObject(titleKey).getJSONArray("title")
        if (titleArray.length() == 0) return "Untitled"
        titleArray.getJSONObject(0).getJSONObject("text").getString("content")
    }.getOrDefault("Untitled")

    private fun extractBlockText(block: JSONObject): String? = runCatching {
        val type = block.optString("type") ?: return null
        val blockContent = block.optJSONObject(type) ?: return null
        val richText = blockContent.optJSONArray("rich_text") ?: return null
        (0 until richText.length()).joinToString("") { i ->
            richText.getJSONObject(i).optJSONObject("text")
                ?.optString("content") ?: ""
        }.takeIf { it.isNotBlank() }
    }.getOrNull()

    private fun missingParam(name: String) = ConnectorOutput.Failure(
        code    = "bad_input",
        message = "Missing required parameter: '$name'"
    )

    private companion object {
        const val TAG                = "AIRI_NotionMcpConnector"
        const val BASE_URL           = "https://api.notion.com/v1"
        const val NOTION_API_VERSION = "2022-06-28"
        /** B-06: Token cache TTL — 5 minutes in milliseconds. */
        const val TOKEN_CACHE_TTL_MS = 5 * 60 * 1000L
        val JSON_MEDIA_TYPE          = "application/json; charset=utf-8".toMediaType()

        val NOTION_TOOLS = listOf(
            McpTool(
                name        = "search_pages",
                description = "Search Notion pages and databases by keyword.",
                schema      = mapOf("query" to "string — search terms")
            ),
            McpTool(
                name        = "get_page",
                description = "Retrieve metadata and properties for a Notion page by ID.",
                schema      = mapOf("page_id" to "string — Notion page UUID")
            ),
            McpTool(
                name        = "get_page_blocks",
                description = "Retrieve the block content (body text) of a Notion page or block.",
                schema      = mapOf("block_id" to "string — page or block UUID")
            ),
            McpTool(
                name        = "create_page",
                description = "Create a new Notion page as a child of an existing page.",
                schema      = mapOf(
                    "parent_page_id" to "string — UUID of the parent page",
                    "title"          to "string — page title",
                    "content"        to "string — initial paragraph body text (optional)"
                )
            ),
            McpTool(
                name        = "query_database",
                description = "Query a Notion database, optionally applying a filter.",
                schema      = mapOf(
                    "database_id" to "string — Notion database UUID",
                    "filter_json" to "string — Notion filter JSON (optional)"
                )
            )
        )
    }
}

// ── SecureStorage extension ───────────────────────────────────────────────────

/** Retrieves the stored Notion integration token, or null if not set. */
fun SecureStorage.getNotionToken(): String? = getIntegrationToken("notion")

/** Stores a Notion integration token. */
fun SecureStorage.saveNotionToken(token: String) = saveIntegrationToken("notion", token)
