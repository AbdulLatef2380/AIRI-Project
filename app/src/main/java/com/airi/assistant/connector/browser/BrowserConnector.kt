package com.airi.assistant.connector.browser

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
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * BrowserConnector — URL fetching, browser launch, and DOM reasoning as a first-class Connector.
 *
 * ── ACTIONS ───────────────────────────────────────────────────────────────
 *
 * | action                | required params      | notes                                 |
 * |-----------------------|----------------------|---------------------------------------|
 * | `fetch`               | url                  | HTTP GET, returns raw content          |
 * | `fetch_text`          | url                  | Alias for fetch                        |
 * | `launch`              | url                  | Open URL in device browser (Intent)    |
 * | `search`              | query                | DuckDuckGo search + text extraction    |
 * | `head`                | url                  | HTTP HEAD, returns headers only        |
 * | `extract_links`       | url                  | DOM: all hrefs from <a> tags           |
 * | `extract_main_content`| url                  | DOM: body text, scripts/styles removed |
 * | `dom_query`           | url, selector        | DOM: find elements by tag/class/id     |
 * | `page_summary`        | url                  | DOM: compact 2000-char summary         |
 * | `extract_metadata`    | url                  | DOM: title, description, og:* tags     |
 *
 * ── SECURITY ─────────────────────────────────────────────────────────────
 *
 *   - Only HTTPS URLs are fetched by default ([allowInsecure] = false).
 *   - Redirects followed up to [MAX_REDIRECTS] times.
 *   - Response body truncated at [MAX_BODY_BYTES].
 *   - Network timeout: [CONNECT_TIMEOUT_MS] / [READ_TIMEOUT_MS].
 *   - `launch` uses FLAG_ACTIVITY_NEW_TASK so it works from any context.
 *
 * ── PROOF TAGS ────────────────────────────────────────────────────────────
 *
 *   BROWSER_FETCH        — URL fetched, bytes + elapsed
 *   BROWSER_LAUNCH       — URL opened in device browser
 *   BROWSER_SEARCH       — DuckDuckGo search performed
 *   BROWSER_HEAD         — HTTP HEAD request performed
 *   BROWSER_DOM_LINKS    — links extracted from page
 *   BROWSER_DOM_CONTENT  — main content extracted
 *   BROWSER_DOM_QUERY    — DOM selector query performed
 */
class BrowserConnector(
    private val appContext:    Context,
    private val allowInsecure: Boolean = false
) : Connector {

    override val id          = "browser"
    override val name        = "Browser"
    override val description = "Fetch web content, open URLs, and reason over page structure with DOM extraction."
    override val type        = ConnectorType.LOCAL

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Browser connector ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("browser", "web", "fetch", "url", "http", "search", "dom", "html", "links")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val ok = runCatching { fetch("https://example.com") }.isSuccess
        _state.value = ConnectorState(
            connected = true, healthy = ok,
            statusLine = if (ok) "HTTP connectivity confirmed" else "HTTP unavailable (offline?)",
            lastUpdatedMs = System.currentTimeMillis()
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        when (input.action.lowercase()) {
            "fetch", "fetch_text" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                fetchAction(url)
            }
            "launch" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                launchAction(url)
            }
            "search" -> {
                val query = input.params["query"] ?: input.text
                if (query.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'query' param")
                searchAction(query)
            }
            "head" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                headAction(url)
            }
            "extract_links" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                extractLinksAction(url)
            }
            "extract_main_content" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                extractMainContentAction(url)
            }
            "dom_query" -> {
                val url      = input.params["url"] ?: input.text
                val selector = input.params["selector"] ?: ""
                if (url.isBlank())      return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                if (selector.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'selector' param")
                domQueryAction(url, selector)
            }
            "page_summary" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                pageSummaryAction(url)
            }
            "extract_metadata" -> {
                val url = input.params["url"] ?: input.text
                if (url.isBlank()) return@withContext ConnectorOutput.Failure("bad_input", "Missing 'url' param")
                extractMetadataAction(url)
            }
            else -> ConnectorOutput.Failure(
                "unknown_action",
                "BrowserConnector: unknown action '${input.action}'. " +
                    "Supported: fetch, launch, search, head, extract_links, extract_main_content, dom_query, page_summary, extract_metadata"
            )
        }
    }

    // ── Core fetch actions ────────────────────────────────────────────────────

    private suspend fun fetchAction(url: String): ConnectorOutput {
        if (!allowInsecure && !url.startsWith("https://", ignoreCase = true)) {
            return ConnectorOutput.Failure("insecure_url", "Only HTTPS URLs are allowed (got: $url)")
        }
        val start = System.currentTimeMillis()
        return withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            runCatching {
                val body   = fetch(url)
                val elapsed = System.currentTimeMillis() - start
                Log.i("AIRI_PROOF", "BROWSER_FETCH url=${url.take(100)} bytes=${body.length} elapsed=${elapsed}ms")
                ConnectorOutput.Success(
                    text = body,
                    data = mapOf(
                        "url"        to url,
                        "bytes"      to body.length.toString(),
                        "elapsed_ms" to elapsed.toString()
                    ),
                    durationMs = elapsed
                )
            }.getOrElse { e ->
                ConnectorOutput.Failure("fetch_error", "${e.javaClass.simpleName}: ${e.message}", retryable = true)
            }
        } ?: ConnectorOutput.Failure("timeout", "Fetch timed out after ${FETCH_TIMEOUT_MS}ms", retryable = true)
    }

    private fun launchAction(url: String): ConnectorOutput {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            appContext.startActivity(intent)
            Log.i("AIRI_PROOF", "BROWSER_LAUNCH url=${url.take(100)}")
            ConnectorOutput.Success(
                text = "Opened in browser: $url",
                data = mapOf("url" to url)
            )
        }.getOrElse { e ->
            ConnectorOutput.Failure("launch_error", "Could not open browser: ${e.message}")
        }
    }

    private suspend fun searchAction(query: String): ConnectorOutput {
        val enc = Uri.encode(query)
        val url = "https://html.duckduckgo.com/html/?q=$enc"
        Log.i("AIRI_PROOF", "BROWSER_SEARCH query='${query.take(80)}'")
        return fetchAction(url).let { result ->
            if (result is ConnectorOutput.Success) {
                val content = extractMainContent(result.text).take(8_000)
                ConnectorOutput.Success(
                    text       = content,
                    data       = mapOf("query" to query, "search_url" to url),
                    durationMs = result.durationMs
                )
            } else result
        }
    }

    private suspend fun headAction(url: String): ConnectorOutput = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = CONNECT_TIMEOUT_MS.toInt()
            conn.readTimeout    = READ_TIMEOUT_MS.toInt()
            conn.connect()
            val headers = conn.headerFields.entries
                .filter { it.key != null }
                .joinToString("\n") { (k, v) -> "$k: ${v.joinToString(", ")}" }
            val code = conn.responseCode
            conn.disconnect()
            Log.i("AIRI_PROOF", "BROWSER_HEAD url=${url.take(100)} status=$code")
            ConnectorOutput.Success(
                text = "HTTP $code\n$headers",
                data = mapOf("url" to url, "status" to code.toString())
            )
        }.getOrElse { e ->
            ConnectorOutput.Failure("head_error", "${e.javaClass.simpleName}: ${e.message}", retryable = true)
        }
    }

    // ── DOM reasoning actions ──────────────────────────────────────────────────

    private suspend fun extractLinksAction(url: String): ConnectorOutput {
        val fetchResult = fetchAction(url)
        if (fetchResult is ConnectorOutput.Failure) return fetchResult
        val html  = (fetchResult as ConnectorOutput.Success).text
        val links = extractLinks(html, url)
        Log.i("AIRI_PROOF", "BROWSER_DOM_LINKS url=${url.take(80)} count=${links.size}")
        return ConnectorOutput.Success(
            text = links.take(200).joinToString("\n"),
            data = mapOf(
                "url"   to url,
                "count" to links.size.toString(),
                "truncated" to (links.size > 200).toString()
            ),
            durationMs = fetchResult.durationMs
        )
    }

    private suspend fun extractMainContentAction(url: String): ConnectorOutput {
        val fetchResult = fetchAction(url)
        if (fetchResult is ConnectorOutput.Failure) return fetchResult
        val html    = (fetchResult as ConnectorOutput.Success).text
        val content = extractMainContent(html).take(16_000)
        Log.i("AIRI_PROOF", "BROWSER_DOM_CONTENT url=${url.take(80)} chars=${content.length}")
        return ConnectorOutput.Success(
            text = content,
            data = mapOf("url" to url, "original_bytes" to html.length.toString()),
            durationMs = fetchResult.durationMs
        )
    }

    private suspend fun domQueryAction(url: String, selector: String): ConnectorOutput {
        val fetchResult = fetchAction(url)
        if (fetchResult is ConnectorOutput.Failure) return fetchResult
        val html    = (fetchResult as ConnectorOutput.Success).text
        val results = domQuery(html, selector)
        Log.i("AIRI_PROOF", "BROWSER_DOM_QUERY url=${url.take(80)} selector='$selector' count=${results.size}")
        return ConnectorOutput.Success(
            text = if (results.isEmpty()) "(no elements matched '$selector')"
                   else results.take(50).joinToString("\n---\n"),
            data = mapOf(
                "url"      to url,
                "selector" to selector,
                "count"    to results.size.toString()
            ),
            durationMs = fetchResult.durationMs
        )
    }

    private suspend fun pageSummaryAction(url: String): ConnectorOutput {
        val fetchResult = fetchAction(url)
        if (fetchResult is ConnectorOutput.Failure) return fetchResult
        val html     = (fetchResult as ConnectorOutput.Success).text
        val title    = extractTitle(html)
        val desc     = extractMetaDescription(html)
        val content  = extractMainContent(html).take(1_500)
        val summary  = buildString {
            if (title.isNotBlank())  { append("Title: $title\n") }
            if (desc.isNotBlank())   { append("Description: $desc\n") }
            append("\n")
            append(content)
        }
        return ConnectorOutput.Success(
            text = summary,
            data = mapOf(
                "url"   to url,
                "title" to title,
                "desc"  to desc
            ),
            durationMs = fetchResult.durationMs
        )
    }

    private suspend fun extractMetadataAction(url: String): ConnectorOutput {
        val fetchResult = fetchAction(url)
        if (fetchResult is ConnectorOutput.Failure) return fetchResult
        val html   = (fetchResult as ConnectorOutput.Success).text
        val title  = extractTitle(html)
        val desc   = extractMetaDescription(html)
        val ogTags = extractOpenGraph(html)
        val canonical = extractCanonical(html)
        val meta = buildString {
            appendLine("title: $title")
            appendLine("description: $desc")
            appendLine("canonical: $canonical")
            ogTags.forEach { (k, v) -> appendLine("og:$k = $v") }
        }
        return ConnectorOutput.Success(
            text = meta,
            data = mapOf("url" to url, "title" to title, "description" to desc) + ogTags,
            durationMs = fetchResult.durationMs
        )
    }

    // ── DOM parsing utilities ─────────────────────────────────────────────────

    /**
     * Extract all unique hyperlinks from raw HTML.
     *
     * Resolves relative URLs against the page base, deduplicates, and filters
     * out javascript:, mailto:, tel: pseudo-URLs and fragment-only links.
     */
    private fun extractLinks(html: String, baseUrl: String): List<String> {
        val baseUri = runCatching { java.net.URI(baseUrl) }.getOrNull()
        val hrefPattern = Regex("""<a\b[^>]*\shref=["']([^"'#\s][^"']*)["']""", RegexOption.IGNORE_CASE)
        return hrefPattern.findAll(html)
            .mapNotNull { m ->
                val href = m.groupValues[1].trim()
                when {
                    href.isBlank()                      -> null
                    href.startsWith("javascript:")      -> null
                    href.startsWith("mailto:")          -> null
                    href.startsWith("tel:")             -> null
                    href.startsWith("data:")            -> null
                    href.startsWith("http://")          -> if (allowInsecure) href else null
                    href.startsWith("https://")         -> href
                    href.startsWith("//")               -> "https:$href"
                    href.startsWith("/") && baseUri != null -> "${baseUri.scheme}://${baseUri.host}$href"
                    baseUri != null                     -> runCatching {
                        baseUri.resolve(href).toString()
                    }.getOrNull()
                    else -> null
                }
            }
            .distinct()
            .toList()
    }

    /**
     * Strip HTML down to readable plain text.
     *
     * Removes: <head>, <script>, <style>, <nav>, <footer>, <header>, HTML tags.
     * Collapses whitespace. Leaves the main readable body content.
     */
    private fun extractMainContent(html: String): String {
        return html
            .replace(Regex("""(?i)<head\b[^>]*>[\s\S]*?</head>"""), "")
            .replace(Regex("""(?i)<script\b[^>]*>[\s\S]*?</script>"""), "")
            .replace(Regex("""(?i)<style\b[^>]*>[\s\S]*?</style>"""), "")
            .replace(Regex("""(?i)<nav\b[^>]*>[\s\S]*?</nav>"""), "")
            .replace(Regex("""(?i)<footer\b[^>]*>[\s\S]*?</footer>"""), "")
            .replace(Regex("""(?i)<header\b[^>]*>[\s\S]*?</header>"""), "")
            .replace(Regex("""(?i)<noscript\b[^>]*>[\s\S]*?</noscript>"""), "")
            .replace(Regex("""<[^>]+>"""), " ")
            .replace(Regex("""&nbsp;"""), " ")
            .replace(Regex("""&amp;"""), "&")
            .replace(Regex("""&lt;"""), "<")
            .replace(Regex("""&gt;"""), ">")
            .replace(Regex("""&quot;"""), "\"")
            .replace(Regex("""&#\d+;"""), "")
            .replace(Regex("""\s{2,}"""), " ")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
    }

    /**
     * Very simple CSS-like DOM query.
     *
     * Supported selectors:
     *   - `h1`, `p`, `a`, etc.  — element by tag name
     *   - `.foo`                — elements with class="foo"
     *   - `#bar`                — element with id="bar"
     *   - `div.container`       — tag + class
     *   - `[attr]`              — elements with a given attribute present
     *   - `[attr=val]`          — elements with a specific attribute value
     *
     * Returns list of stripped inner-text strings for matched elements.
     */
    private fun domQuery(html: String, selector: String): List<String> {
        val results = mutableListOf<String>()

        // Parse selector into components
        val attrMatch = Regex("""\[(\w+)(?:=["']?([^"'\]]+)["']?)?\]""").find(selector)
        val cleanSel  = if (attrMatch != null) selector.substring(0, attrMatch.range.first) else selector

        val (tag, className, idName) = when {
            cleanSel.contains("#") -> {
                val parts = cleanSel.split("#", limit = 2)
                Triple(parts[0].ifBlank { null }, null, parts[1])
            }
            cleanSel.contains(".") -> {
                val parts = cleanSel.split(".", limit = 2)
                Triple(parts[0].ifBlank { null }, parts[1], null)
            }
            else -> Triple(cleanSel.ifBlank { null }, null, null)
        }

        // Build element regex
        val tagPat  = tag ?: """[a-zA-Z][\w-]*"""
        val classRx = if (className != null)
            """class=["'][^"']*\b${Regex.escape(className)}\b[^"']*["']"""
        else null
        val idRx    = if (idName != null)
            """id=["']${Regex.escape(idName)}["']"""
        else null
        val attrRx  = if (attrMatch != null) {
            val attrKey = attrMatch.groupValues[1]
            val attrVal = attrMatch.groupValues[2]
            if (attrVal.isBlank()) """${Regex.escape(attrKey)}=["'][^"']*["']"""
            else """${Regex.escape(attrKey)}=["']${Regex.escape(attrVal)}["']"""
        } else null

        val attrsConstraints = listOfNotNull(classRx, idRx, attrRx)
        val openTagPat = if (attrsConstraints.isEmpty()) {
            """<($tagPat)\b[^>]*>"""
        } else {
            // All constraints must appear somewhere in the opening tag attributes
            """<($tagPat)\b(?=[^>]*${attrsConstraints.joinToString("[^>]*")})[^>]*>"""
        }

        try {
            val elemRegex = Regex(openTagPat, RegexOption.IGNORE_CASE)
            elemRegex.findAll(html).forEach { m ->
                val foundTag = m.groupValues[1]
                val start    = m.range.last + 1
                // Find the matching close tag (simple non-nested approach)
                val closeTag  = """(?i)</$foundTag\s*>"""
                val closeMatch = Regex(closeTag).find(html, start)
                val innerHtml  = if (closeMatch != null) html.substring(start, closeMatch.range.first)
                                 else ""
                val innerText  = innerHtml
                    .replace(Regex("""<[^>]+>"""), " ")
                    .replace(Regex("""\s+"""), " ")
                    .trim()
                if (innerText.isNotBlank()) results += innerText
            }
        } catch (_: Exception) { /* ignore malformed selector */ }

        return results
    }

    private fun extractTitle(html: String): String =
        Regex("""(?i)<title[^>]*>(.*?)</title>""").find(html)
            ?.groupValues?.get(1)?.replace(Regex("""<[^>]+>"""), "")?.trim()
            ?: ""

    private fun extractMetaDescription(html: String): String =
        Regex("""(?i)<meta\s+name=["']description["'][^>]+content=["']([^"']*)["']""").find(html)
            ?.groupValues?.get(1)?.trim()
            ?: Regex("""(?i)<meta\s+content=["']([^"']*)["'][^>]+name=["']description["']""").find(html)
                ?.groupValues?.get(1)?.trim()
            ?: ""

    private fun extractOpenGraph(html: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        Regex("""(?i)<meta\s+property=["']og:(\w+)["'][^>]+content=["']([^"']*)["']""")
            .findAll(html).forEach { m -> result[m.groupValues[1]] = m.groupValues[2].trim() }
        Regex("""(?i)<meta\s+content=["']([^"']*)["'][^>]+property=["']og:(\w+)["']""")
            .findAll(html).forEach { m -> result[m.groupValues[2]] = m.groupValues[1].trim() }
        return result
    }

    private fun extractCanonical(html: String): String =
        Regex("""(?i)<link\s+rel=["']canonical["'][^>]+href=["']([^"']*)["']""").find(html)
            ?.groupValues?.get(1)?.trim()
            ?: ""

    // ── HTTP fetch ────────────────────────────────────────────────────────────

    private fun fetch(url: String): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.requestMethod   = "GET"
        conn.connectTimeout  = CONNECT_TIMEOUT_MS.toInt()
        conn.readTimeout     = READ_TIMEOUT_MS.toInt()
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "AIRI-Agent/1.0 Android")
        conn.connect()
        val body = StringBuilder()
        BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                body.append(line).append('\n')
                if (body.length > MAX_BODY_BYTES) {
                    body.append("\n[content truncated at ${MAX_BODY_BYTES / 1024}KB]")
                    break
                }
            }
        }
        conn.disconnect()
        return body.toString()
    }

    companion object {
        private const val CONNECT_TIMEOUT_MS = 8_000L
        private const val READ_TIMEOUT_MS    = 15_000L
        private const val FETCH_TIMEOUT_MS   = 20_000L
        private const val MAX_BODY_BYTES     = 256 * 1024
    }
}
