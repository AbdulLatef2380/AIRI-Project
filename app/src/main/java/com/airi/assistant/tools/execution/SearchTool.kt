package com.airi.assistant.tools.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * SearchTool — multi-backend web search and page content extraction.
 *
 * Search backends (evaluated in order):
 *
 *   1. [searchBrave]       — Brave Search API (real web results, 20 ranked URLs + snippets)
 *                            Requires Brave API key in SecureApiKeyStore(BRAVE).
 *                            Free tier: 2000 queries/month.
 *                            Endpoint: https://api.search.brave.com/res/v1/web/search
 *
 *   2. [searchDuckDuckGo]  — DDG Instant Answers (free, no key, ~30% coverage)
 *                            Used as fallback when no Brave key is configured.
 *
 *   3. [searchViaIntent]   — Opens browser (last resort, no content returned).
 *
 * Page content extraction:
 *
 *   [fetchViaJina]         — Jina AI Reader (r.jina.ai/{url}) — returns clean markdown
 *                            from any URL. Free: 1000 requests/day with no key.
 *                            Used by the fetch_url agent tool and by searchBrave
 *                            to enrich the top result with full page content.
 *
 *   [fetchPageContent]     — Direct OkHttp fetch with regex HTML stripping.
 *                            Fallback when Jina is unavailable or rate-limited.
 */
class SearchTool(
    private val context: Context,
    private val braveApiKey: String? = null   // injected from SecureApiKeyStore
) {
    private val TAG = "SearchTool"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "AIRI-Assistant/2.0 (Android; ai-research-agent)")
                    .header("Accept-Language", "en-US,en;q=0.9")
                    .build()
            )
        }
        .build()

    // ──────────────────────────────────────────────────────────────────────────
    // PRIMARY: Brave Search API
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Query Brave Search API for real web results.
     *
     * Returns up to [count] ranked results with title, URL, and description.
     * Optionally enriches the top result with Jina-extracted page content.
     *
     * API docs: https://api.search.brave.com/app/documentation/web-search
     */
    suspend fun searchBrave(
        query:    String,
        count:    Int = 5,
        enrich:   Boolean = true    // fetch top result via Jina for full content
    ): BraveSearchResult = withContext(Dispatchers.IO) {
        val key = braveApiKey?.takeIf { it.isNotBlank() }
            ?: return@withContext BraveSearchResult(
                success = false, query = query, results = emptyList(),
                error = "No Brave API key configured. Add it in Settings → AI Models → Manage API Keys."
            )

        val url = "https://api.search.brave.com/res/v1/web/search" +
            "?q=${urlEncode(query)}&count=$count&search_lang=en&safesearch=moderate"

        Log.i(TAG, "AIRI_RUNTIME BRAVE_SEARCH query=${query.take(60)}")

        return@withContext try {
            val response = httpClient.newCall(
                Request.Builder()
                    .url(url)
                    .header("Accept", "application/json")
                    .header("Accept-Encoding", "gzip")
                    .header("X-Subscription-Token", key)
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                val code = response.code
                val body = response.body?.string()?.take(200) ?: ""
                Log.w(TAG, "Brave search HTTP $code: $body")
                return@withContext BraveSearchResult(
                    success = false, query = query, results = emptyList(),
                    error = "Brave search HTTP $code"
                )
            }

            val body = response.body?.string() ?: ""
            val json = JSONObject(body)
            val webResults = json.optJSONObject("web")?.optJSONArray("results") ?: JSONArray()

            val results = mutableListOf<SearchWebResult>()
            for (i in 0 until webResults.length()) {
                val item = webResults.getJSONObject(i)
                results += SearchWebResult(
                    title       = item.optString("title"),
                    url         = item.optString("url"),
                    description = item.optString("description"),
                    age         = item.optString("age")       // e.g. "2 days ago"
                )
            }

            Log.i(TAG, "AIRI_RUNTIME BRAVE_SEARCH_OK query=${query.take(60)} results=${results.size}")

            // Enrich top result with full page content via Jina
            val topContent: String? = if (enrich && results.isNotEmpty()) {
                val top = results.first()
                val jinaResult = runCatching { fetchViaJina(top.url, maxChars = 3000) }.getOrNull()
                if (jinaResult?.success == true) jinaResult.content else null
            } else null

            BraveSearchResult(
                success      = true,
                query        = query,
                results      = results,
                topContent   = topContent,
                formattedSummary = formatBraveResults(results, topContent, query)
            )

        } catch (e: Exception) {
            Log.e(TAG, "Brave search failed: ${e.message}")
            BraveSearchResult(success = false, query = query, results = emptyList(),
                error = "Search error: ${e.message?.take(100)}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // PAGE CONTENT: Jina AI Reader
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Fetch and extract clean markdown text from [url] using Jina AI Reader.
     *
     * Jina Reader transforms any web page into readable markdown by:
     *   - Removing navigation, ads, and boilerplate
     *   - Rendering JavaScript-heavy pages
     *   - Returning structured markdown with headings and lists
     *
     * Free tier: 1000 requests/day with no API key.
     * Endpoint: https://r.jina.ai/{url}
     *
     * @param maxChars Maximum characters to return (default 4000)
     */
    suspend fun fetchViaJina(url: String, maxChars: Int = 4000): FetchResult =
        withContext(Dispatchers.IO) {
        Log.d(TAG, "AIRI_RUNTIME JINA_FETCH url=${url.take(80)}")
        return@withContext try {
            val jinaUrl = "https://r.jina.ai/${url}"
            val response = httpClient.newCall(
                Request.Builder()
                    .url(jinaUrl)
                    .header("Accept", "text/markdown, text/plain")
                    .header("X-Return-Format", "markdown")
                    .header("X-Remove-Selector", "nav, header, footer, .ads, #sidebar")
                    .build()
            ).execute()

            if (!response.isSuccessful) {
                return@withContext FetchResult(success = false, url = url,
                    content = "Jina fetch HTTP ${response.code}")
            }

            val raw = response.body?.string()?.trim() ?: ""
            // Strip the Jina preamble line "Title: ...\nURL: ...\nPublished..."
            val content = raw
                .lines()
                .dropWhile { it.startsWith("Title:") || it.startsWith("URL:") ||
                    it.startsWith("Published") || it.startsWith("Description:") || it.isBlank() }
                .joinToString("\n")
                .take(maxChars)
                .trim()

            Log.d(TAG, "AIRI_RUNTIME JINA_FETCH_OK url=${url.take(60)} chars=${content.length}")
            FetchResult(success = content.isNotBlank(), url = url, content = content)

        } catch (e: Exception) {
            Log.e(TAG, "Jina fetch failed for $url: ${e.message}")
            FetchResult(success = false, url = url, content = "Fetch error: ${e.message?.take(80)}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FALLBACK: DuckDuckGo Instant Answers
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Query DuckDuckGo Instant Answers API.
     * Free, no key, ~30% coverage of general queries.
     * Used as fallback when no Brave key is configured.
     */
    suspend fun searchDuckDuckGo(query: String): SearchResult = withContext(Dispatchers.IO) {
        val url = "https://api.duckduckgo.com/?q=${urlEncode(query)}&format=json&no_html=1&skip_disambig=1"
        Log.d(TAG, "DDG query: ${query.take(60)}")
        return@withContext try {
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            val body     = response.body?.string() ?: return@withContext noResult(query)
            val json     = JSONObject(body)

            val abstract_     = json.optString("Abstract").trim()
            val definition    = json.optString("Definition").trim()
            val answer        = json.optString("Answer").trim()
            val relatedTopics = json.optJSONArray("RelatedTopics")

            val summary = when {
                answer.isNotBlank()     -> answer
                abstract_.isNotBlank()  -> abstract_
                definition.isNotBlank() -> definition
                else -> {
                    val topics = mutableListOf<String>()
                    if (relatedTopics != null) {
                        for (i in 0 until minOf(relatedTopics.length(), 5)) {
                            val text = relatedTopics.optJSONObject(i)?.optString("Text")?.trim() ?: continue
                            if (text.isNotBlank()) topics += text
                        }
                    }
                    topics.joinToString("\n").ifBlank { "" }
                }
            }

            val sourceUrl = json.optString("AbstractURL").ifBlank { json.optString("DefinitionURL") }
            SearchResult(success = summary.isNotBlank(), query = query,
                summary = summary.ifBlank { "No instant answer available for: $query" },
                sourceUrl = sourceUrl)
        } catch (e: Exception) {
            Log.e(TAG, "DDG failed: ${e.message}")
            SearchResult(success = false, query = query, summary = "Search error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // LAST RESORT: Intent-based search
    // ──────────────────────────────────────────────────────────────────────────

    fun searchViaIntent(query: String): SearchResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
            } else {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://www.google.com/search?q=${urlEncode(query)}")
                    ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                )
            }
            SearchResult(success = true, query = query,
                summary = "Search opened in browser for: $query")
        } catch (e: Exception) {
            SearchResult(success = false, query = query, summary = "Search failed: ${e.message}")
        }
    }

    fun openInBrowser(url: String): Boolean {
        return try {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url))
                    .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            )
            true
        } catch (e: Exception) { false }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // DIRECT FETCH (legacy fallback)
    // ──────────────────────────────────────────────────────────────────────────

    suspend fun fetchPageContent(url: String, maxLen: Int = 4000): FetchResult =
        withContext(Dispatchers.IO) {
        return@withContext try {
            val response = httpClient.newCall(Request.Builder().url(url).build()).execute()
            if (!response.isSuccessful) return@withContext FetchResult(false, url, "HTTP ${response.code}")
            val raw  = response.body?.string() ?: ""
            val text = raw
                .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<style[^>]*>.*?</style>""",  RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .take(maxLen)
            FetchResult(success = true, url = url, content = text)
        } catch (e: Exception) {
            FetchResult(false, url, "Fetch error: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Formatting helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun formatBraveResults(
        results:    List<SearchWebResult>,
        topContent: String?,
        query:      String
    ): String {
        if (results.isEmpty()) return "No results found for: $query"
        val sb = StringBuilder()
        sb.append("Web search results for: \"$query\"\n\n")

        // If we have full content for the top result, lead with it
        if (topContent != null && topContent.isNotBlank()) {
            val top = results.first()
            sb.append("### ${top.title}\n")
            sb.append("Source: ${top.url}\n")
            if (top.age.isNotBlank()) sb.append("Published: ${top.age}\n")
            sb.append("\n$topContent\n\n")
            if (results.size > 1) {
                sb.append("### Other results:\n")
                results.drop(1).forEach { r ->
                    sb.append("• **${r.title}** — ${r.url}\n")
                    if (r.description.isNotBlank()) sb.append("  ${r.description.take(120)}\n")
                }
            }
        } else {
            // Snippet-only format when Jina unavailable
            results.forEachIndexed { idx, r ->
                sb.append("${idx + 1}. **${r.title}**\n")
                sb.append("   URL: ${r.url}\n")
                if (r.description.isNotBlank()) sb.append("   ${r.description.take(200)}\n")
                if (r.age.isNotBlank()) sb.append("   (${r.age})\n")
                sb.append("\n")
            }
        }
        return sb.toString().take(6000)
    }

    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun noResult(q: String) = SearchResult(false, q, "No result for: $q")

    // ──────────────────────────────────────────────────────────────────────────
    // Data types
    // ──────────────────────────────────────────────────────────────────────────

    data class SearchResult(
        val success:   Boolean,
        val query:     String,
        val summary:   String,
        val sourceUrl: String = ""
    )

    data class SearchWebResult(
        val title:       String,
        val url:         String,
        val description: String,
        val age:         String = ""
    )

    data class BraveSearchResult(
        val success:          Boolean,
        val query:            String,
        val results:          List<SearchWebResult>,
        val topContent:       String?  = null,
        val formattedSummary: String   = "",
        val error:            String   = ""
    ) {
        /** Convenience: the string to return to the agent as the tool result. */
        fun toAgentString(): String = when {
            !success        -> error.ifBlank { "Search failed for: $query" }
            formattedSummary.isNotBlank() -> formattedSummary
            results.isNotEmpty() -> results.joinToString("\n") { "• ${it.title}: ${it.url}" }
            else -> "No results for: $query"
        }
    }

    data class FetchResult(
        val success:  Boolean,
        val url:      String,
        val content:  String
    )
}
