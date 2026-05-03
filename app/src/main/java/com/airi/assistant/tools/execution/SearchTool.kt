package com.airi.assistant.tools.execution

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * SearchTool — real search execution with multiple backends.
 *
 * Backends (in priority order):
 *
 *   1. [searchViaIntent]  — fires ACTION_WEB_SEARCH, opens the default browser/search app.
 *      No permissions, no API key. Always works. UI-visible.
 *
 *   2. [fetchPageContent] — fetches raw HTML/text from any URL via OkHttp.
 *      Used for targeted URL lookups (no API key required).
 *
 *   3. [searchDuckDuckGo] — uses DuckDuckGo Instant Answers API (free, no key).
 *      Returns structured instant answer if available.
 *
 * For LLM augmentation, combine [searchDuckDuckGo] result with an LLM summary pass.
 */
class SearchTool(private val context: Context) {

    private val TAG = "SearchTool"

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            chain.proceed(
                chain.request().newBuilder()
                    .header("User-Agent", "AIRI-Assistant/1.0")
                    .build()
            )
        }
        .build()

    // ─────────────────────────────────────────────────────────────────────────
    // Intent-based (no API key, opens browser)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Launch a web search in the user's default browser/search app.
     * No permissions or API key required.
     *
     * @param query The search query
     */
    fun searchViaIntent(query: String): SearchResult {
        return try {
            val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                putExtra("query", query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                SearchResult(success = true, query = query, summary = "Search opened for: $query")
            } else {
                // Fallback: open browser with Google search
                openInBrowser("https://www.google.com/search?q=${urlEncode(query)}")
                SearchResult(success = true, query = query, summary = "Browser search opened for: $query")
            }
        } catch (e: Exception) {
            Log.e(TAG, "searchViaIntent failed: ${e.message}")
            SearchResult(success = false, query = query, summary = "Search failed: ${e.message}")
        }
    }

    /**
     * Open a URL directly in the default browser.
     */
    fun openInBrowser(url: String): Boolean {
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            Log.i(TAG, "Opened browser: $url")
            true
        } catch (e: Exception) {
            Log.e(TAG, "openInBrowser failed: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DuckDuckGo Instant Answers (free, no key)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Query DuckDuckGo Instant Answers API for structured results.
     *
     * Returns an instant answer (Wikipedia abstracts, definitions, calculations, etc.)
     * if available. Falls back to an empty result for queries with no instant answer.
     *
     * Free API: https://api.duckduckgo.com/?q=<query>&format=json&no_html=1
     */
    suspend fun searchDuckDuckGo(query: String): SearchResult = withContext(Dispatchers.IO) {
        val url = "https://api.duckduckgo.com/?q=${urlEncode(query)}&format=json&no_html=1&skip_disambig=1"
        Log.d(TAG, "DuckDuckGo query: $query")
        return@withContext try {
            val response = httpClient.newCall(
                Request.Builder().url(url).build()
            ).execute()

            val body = response.body?.string() ?: return@withContext noResult(query)
            val json = JSONObject(body)

            val abstract_  = json.optString("Abstract").trim()
            val definition = json.optString("Definition").trim()
            val answer     = json.optString("Answer").trim()
            val relatedTopics = json.optJSONArray("RelatedTopics")

            val summary = when {
                answer.isNotBlank()     -> answer
                abstract_.isNotBlank()  -> abstract_
                definition.isNotBlank() -> definition
                else -> {
                    // Extract related topic texts
                    val topics = mutableListOf<String>()
                    if (relatedTopics != null) {
                        for (i in 0 until minOf(relatedTopics.length(), 5)) {
                            val topic = relatedTopics.optJSONObject(i)
                            val text  = topic?.optString("Text")?.trim() ?: continue
                            if (text.isNotBlank()) topics += text
                        }
                    }
                    topics.joinToString("\n").ifBlank { "No instant answer available for: $query" }
                }
            }

            val sourceUrl = json.optString("AbstractURL").ifBlank {
                json.optString("DefinitionURL")
            }

            Log.d(TAG, "DuckDuckGo result: ${summary.take(80)}")
            SearchResult(
                success   = summary.isNotBlank(),
                query     = query,
                summary   = summary,
                sourceUrl = sourceUrl
            )
        } catch (e: Exception) {
            Log.e(TAG, "searchDuckDuckGo failed: ${e.message}")
            SearchResult(success = false, query = query, summary = "Search error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // URL content fetch
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Fetch text content from a URL.
     * Returns raw text (HTML stripped via basic regex — use for structured APIs / plain text pages).
     *
     * @param url    The URL to fetch
     * @param maxLen Maximum characters to return (default 4000)
     */
    suspend fun fetchPageContent(url: String, maxLen: Int = 4000): FetchResult = withContext(Dispatchers.IO) {
        return@withContext try {
            val response = httpClient.newCall(
                Request.Builder().url(url).build()
            ).execute()

            if (!response.isSuccessful) {
                return@withContext FetchResult(success = false, url = url,
                    content = "HTTP ${response.code}")
            }

            val raw = response.body?.string() ?: ""
            // Strip HTML tags for readability
            val text = raw
                .replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<style[^>]*>.*?</style>""",  RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("""<[^>]+>"""), " ")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
                .take(maxLen)

            Log.d(TAG, "Fetched ${text.length} chars from $url")
            FetchResult(success = true, url = url, content = text)
        } catch (e: Exception) {
            Log.e(TAG, "fetchPageContent failed for $url: ${e.message}")
            FetchResult(success = false, url = url, content = "Fetch error: ${e.message}")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun urlEncode(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun noResult(query: String) = SearchResult(
        success = false, query = query, summary = "No result for: $query"
    )

    // ─────────────────────────────────────────────────────────────────────────
    // Data types
    // ─────────────────────────────────────────────────────────────────────────

    data class SearchResult(
        val success:   Boolean,
        val query:     String,
        val summary:   String,
        val sourceUrl: String = ""
    )

    data class FetchResult(
        val success:  Boolean,
        val url:      String,
        val content:  String
    )
}
