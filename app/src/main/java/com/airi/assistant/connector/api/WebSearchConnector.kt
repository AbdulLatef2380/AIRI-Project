package com.airi.assistant.connector.api

import android.content.Context
import android.content.Intent
import android.net.Uri
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
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * WebSearchConnector — web search and page fetch via DuckDuckGo Instant Answer API.
 *
 * Uses the DuckDuckGo Instant Answer API (free, no key required) for factual
 * lookups, and falls back to launching a browser Intent for full searches.
 * For page content, fetches raw HTML and extracts visible text.
 *
 * ## Supported actions
 * | action          | params               | notes                              |
 * |-----------------|----------------------|------------------------------------|
 * | `instant_answer`| `query`              | DDG Instant Answer (definition/fact)|
 * | `fetch_page`    | `url`                | Fetches page HTML → text extract   |
 * | `open_browser`  | `query` or `url`     | Opens browser Intent (no return)   |
 * | `suggest`       | `query`              | DuckDuckGo autocomplete suggestions|
 *
 * Privacy: only the search query is sent to DDG over HTTPS. No user
 * identifiers, device IDs, or session tokens are included.
 */
class WebSearchConnector(private val context: Context) : Connector {

    override val id          = "web_search"
    override val name        = "Web Search"
    override val description = "DuckDuckGo instant answers and page fetching (no API key)"
    override val type        = ConnectorType.API

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "AIRI-Android/1.0 (educational; open-source)")
                    .build()
            )
        }
        .build()

    private val _state = MutableStateFlow(ConnectorState(connected = true,
        statusLine = "DuckDuckGo API (free)"))
    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()
    override fun meta() = ConnectorMeta(id, name, description, type,
        tags = listOf("search", "web", "duckduckgo", "fetch", "browse"),
        iconUrl = "https://duckduckgo.com/favicon.png")

    override suspend fun connect(): ConnectorState {
        val s = ConnectorState(connected = true, healthy = true,
            statusLine = "DuckDuckGo Instant Answer API ready")
        _state.value = s
        return s
    }

    override suspend fun disconnect() { /* stateless */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val q = (input.params["query"] ?: input.text).trim()
        try {
            when (input.action) {
                "instant_answer" -> instantAnswer(q)
                "fetch_page"     -> fetchPage(input.params["url"] ?: q)
                "open_browser"   -> openBrowser(input.params["url"] ?: buildSearchUrl(q))
                "suggest"        -> suggest(q)
                else -> ConnectorOutput.Failure("unknown_action",
                    "WebSearchConnector does not support: ${input.action}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebSearchConnector error: ${e.message}")
            ConnectorOutput.Failure("search_error", e.message ?: "Unknown error", retryable = true)
        }
    }

    // ── Instant Answer ────────────────────────────────────────────────────────

    private fun instantAnswer(query: String): ConnectorOutput {
        if (query.isBlank()) return ConnectorOutput.Failure("missing_param", "query must not be empty")

        val url  = "https://api.duckduckgo.com/?q=${Uri.encode(query)}&format=json&no_html=1&skip_disambig=1"
        val body = fetch(url) ?: return ConnectorOutput.Failure("network_error",
            "Could not reach DuckDuckGo API", retryable = true)

        return try {
            val json     = JSONObject(body)
            val abstract = json.optString("AbstractText", "")
            val answer   = json.optString("Answer", "")
            val source   = json.optString("AbstractSource", "")
            val abstractUrl = json.optString("AbstractURL", "")

            // Related topics
            val related  = json.optJSONArray("RelatedTopics")
            val topics   = JSONArray()
            if (related != null) {
                for (i in 0 until minOf(related.length(), 5)) {
                    val topic = related.optJSONObject(i) ?: continue
                    val text  = topic.optString("Text", "")
                    if (text.isNotBlank()) topics.put(text)
                }
            }

            val resultText = when {
                answer.isNotBlank()   -> "Answer: $answer"
                abstract.isNotBlank() -> "$abstract\n\nSource: $source ($abstractUrl)"
                topics.length() > 0   -> "Related:\n" + (0 until topics.length()).joinToString("\n") { "• ${topics.getString(it)}" }
                else                  -> "No instant answer found for '$query'. Try fetch_page or open_browser."
            }

            ConnectorOutput.Success(
                text = resultText,
                data = mapOf(
                    "abstract"     to abstract,
                    "answer"       to answer,
                    "source"       to source,
                    "source_url"   to abstractUrl,
                    "topics_count" to topics.length().toString()
                )
            )
        } catch (e: Exception) {
            ConnectorOutput.Failure("parse_error", "Failed to parse DDG response: ${e.message}")
        }
    }

    // ── Page fetch + text extraction ──────────────────────────────────────────

    private fun fetchPage(url: String): ConnectorOutput {
        if (url.isBlank()) return ConnectorOutput.Failure("missing_param", "url required")
        val safeUrl = if (!url.startsWith("http")) "https://$url" else url

        val body = fetch(safeUrl) ?: return ConnectorOutput.Failure("network_error",
            "Could not fetch $safeUrl", retryable = true)

        // Minimal HTML → text extraction (no Jsoup dependency — regex strip)
        val text = body
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>"), "")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>"), "")
            .replace(Regex("<[^>]+>"), " ")
            .replace(Regex("\\s+"), " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&nbsp;", " ")
            .trim()
            .take(4_000)   // Cap to avoid context overflow

        Log.i(TAG, "PAGE_FETCH url=$safeUrl chars=${text.length}")
        return ConnectorOutput.Success(
            text = text,
            data = mapOf("url" to safeUrl, "char_count" to text.length.toString())
        )
    }

    // ── Browser Intent ────────────────────────────────────────────────────────

    private fun openBrowser(url: String): ConnectorOutput {
        val safeUrl = if (!url.startsWith("http")) buildSearchUrl(url) else url
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(safeUrl))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
            ConnectorOutput.Success("Opened browser: $safeUrl", data = mapOf("url" to safeUrl))
        } catch (e: Exception) {
            ConnectorOutput.Failure("intent_failed", "Could not open browser: ${e.message}")
        }
    }

    // ── Autocomplete suggestions ──────────────────────────────────────────────

    private fun suggest(query: String): ConnectorOutput {
        if (query.isBlank()) return ConnectorOutput.Failure("missing_param", "query required")
        val url  = "https://duckduckgo.com/ac/?q=${Uri.encode(query)}&type=list"
        val body = fetch(url) ?: return ConnectorOutput.Failure("network_error",
            "Could not reach DDG autocomplete", retryable = true)

        return try {
            val arr  = JSONArray(body)
            val sugg = if (arr.length() > 1) arr.getJSONArray(1) else JSONArray()
            val list = (0 until sugg.length()).map { sugg.getString(it) }
            ConnectorOutput.Success(
                text = list.joinToString(", "),
                data = mapOf("suggestions_json" to sugg.toString(), "count" to list.size.toString())
            )
        } catch (e: Exception) {
            ConnectorOutput.Failure("parse_error", "Failed to parse suggestions: ${e.message}")
        }
    }

    // ── HTTP helper ───────────────────────────────────────────────────────────

    private fun fetch(url: String): String? {
        return try {
            val req  = Request.Builder().url(url).build()
            val resp = client.newCall(req).execute()
            if (!resp.isSuccessful) { Log.w(TAG, "HTTP ${resp.code} from $url"); return null }
            resp.body?.string()
        } catch (e: Exception) {
            Log.e(TAG, "fetch error: ${e.message}")
            null
        }
    }

    private fun buildSearchUrl(query: String) =
        "https://duckduckgo.com/?q=${Uri.encode(query)}"

    companion object { private const val TAG = "AIRI_WebSearchConnector" }
}
