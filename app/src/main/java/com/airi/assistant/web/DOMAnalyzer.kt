package com.airi.assistant.web

import android.util.Log

/**
 * DOMAnalyzer — HTML content analysis and structured extraction.
 *
 * Pure Kotlin, no external DOM library required. Operates on raw HTML strings
 * fetched by [WebViewController] or [BrowserConnector].
 *
 * ── CAPABILITIES ─────────────────────────────────────────────────────────────
 *
 *  | Extractor              | Description                                     |
 *  |------------------------|-------------------------------------------------|
 *  | [extractText]          | Strip tags, collapse whitespace → clean text    |
 *  | [extractTitle]         | <title> or <h1> content                         |
 *  | [extractLinks]         | All <a href="..."> links with anchor text        |
 *  | [extractMetadata]      | <meta> tags, og:*, twitter:* cards               |
 *  | [extractForms]         | <form> action/method + input names/types        |
 *  | [extractHeadings]      | h1–h6 in document order                         |
 *  | [summarize]            | Compact readable summary (≤ maxChars)           |
 *  | [detectPageType]       | Login, Search, Article, Listing, Error, Unknown |
 *
 * ── SAFETY ───────────────────────────────────────────────────────────────────
 *
 *   All functions accept raw untrusted HTML. Regex-based parsing is used
 *   intentionally (no eval, no JS execution) — safe for use in a sandboxed
 *   worker that cannot run arbitrary code.
 */
object DOMAnalyzer {

    private const val TAG = "DOMAnalyzer"

    // ── Data types ────────────────────────────────────────────────────────────

    data class ExtractedLink(val href: String, val text: String)

    data class ExtractedMeta(
        val title:       String,
        val description: String,
        val ogTitle:     String,
        val ogDesc:      String,
        val ogImage:     String,
        val canonical:   String,
        val author:      String,
        val keywords:    List<String>,
    )

    data class ExtractedForm(
        val action:  String,
        val method:  String,
        val inputs:  List<FormInput>,
    )

    data class FormInput(
        val name:        String,
        val type:        String,
        val placeholder: String,
        val required:    Boolean,
    )

    data class ExtractedHeading(val level: Int, val text: String)

    enum class PageType {
        LOGIN, SEARCH_RESULTS, ARTICLE, PRODUCT_LISTING, ERROR, GENERIC
    }

    // ── Text extraction ───────────────────────────────────────────────────────

    /**
     * Strip HTML tags and return clean readable text.
     * Handles scripts, styles, and HTML entities.
     */
    fun extractText(html: String, maxChars: Int = 8_000): String {
        return html
            .replace(Regex("<script[^>]*>[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[^>]*>[\\s\\S]*?</style>",   RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"),                          " ")
            .decodeEntities()
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(maxChars)
    }

    /**
     * Extract the page title from <title> or first <h1>.
     */
    fun extractTitle(html: String): String {
        val title = Regex("<title[^>]*>([^<]+)</title>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.decodeEntities()?.trim()
        if (!title.isNullOrBlank()) return title

        val h1 = Regex("<h1[^>]*>([^<]+)</h1>", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1)?.decodeEntities()?.trim()
        return h1 ?: "Untitled"
    }

    /**
     * Extract all hyperlinks with anchor text.
     * Filters javascript:, mailto:, data:, and blank hrefs.
     */
    fun extractLinks(html: String, baseUrl: String = ""): List<ExtractedLink> {
        return Regex("""<a[^>]+href=["']([^"'#]*?)["'][^>]*>([\s\S]*?)</a>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .mapNotNull { m ->
                val href = m.groupValues[1].trim()
                val text = extractText(m.groupValues[2]).take(120).trim()
                if (href.isBlank() || href.startsWith("javascript:") ||
                    href.startsWith("mailto:") || href.startsWith("data:")) return@mapNotNull null
                val resolved = if (href.startsWith("http")) href else resolveUrl(baseUrl, href)
                ExtractedLink(resolved, text)
            }
            .take(100)
            .toList()
    }

    /**
     * Extract <meta> and Open Graph metadata.
     */
    fun extractMetadata(html: String): ExtractedMeta {
        fun meta(name: String) = Regex(
            """<meta[^>]+(?:name|property)=["']${Regex.escape(name)}["'][^>]+content=["']([^"']*?)["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?:
        Regex(
            """<meta[^>]+content=["']([^"']*?)["'][^>]+(?:name|property)=["']${Regex.escape(name)}["']""",
            RegexOption.IGNORE_CASE
        ).find(html)?.groupValues?.get(1) ?: ""

        val canonical = Regex("""<link[^>]+rel=["']canonical["'][^>]+href=["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(html)?.groupValues?.get(1) ?: ""
        val keywords = meta("keywords").split(",").map { it.trim() }.filter { it.isNotBlank() }

        return ExtractedMeta(
            title       = extractTitle(html),
            description = meta("description").ifBlank { meta("og:description") },
            ogTitle     = meta("og:title"),
            ogDesc      = meta("og:description"),
            ogImage     = meta("og:image"),
            canonical   = canonical,
            author      = meta("author").ifBlank { meta("article:author") },
            keywords    = keywords,
        )
    }

    /**
     * Extract form definitions (action, method, inputs).
     */
    fun extractForms(html: String): List<ExtractedForm> {
        return Regex("""<form([^>]*)>([\s\S]*?)</form>""", RegexOption.IGNORE_CASE)
            .findAll(html)
            .map { m ->
                val attrs  = m.groupValues[1]
                val body   = m.groupValues[2]
                val action = attrValue(attrs, "action")
                val method = attrValue(attrs, "method").ifBlank { "get" }.uppercase()
                val inputs = Regex("""<input([^>]*)>""", RegexOption.IGNORE_CASE)
                    .findAll(body)
                    .map { im ->
                        val a = im.groupValues[1]
                        FormInput(
                            name        = attrValue(a, "name"),
                            type        = attrValue(a, "type").ifBlank { "text" },
                            placeholder = attrValue(a, "placeholder"),
                            required    = "required" in a.lowercase(),
                        )
                    }
                    .filter { it.name.isNotBlank() }
                    .toList()
                ExtractedForm(action, method, inputs)
            }
            .take(10)
            .toList()
    }

    /**
     * Extract document headings h1–h6.
     */
    fun extractHeadings(html: String): List<ExtractedHeading> {
        return (1..6).flatMap { level ->
            Regex("<h$level[^>]*>([^<]+)</h$level>", RegexOption.IGNORE_CASE)
                .findAll(html)
                .map { ExtractedHeading(level, it.groupValues[1].decodeEntities().trim()) }
                .toList()
        }
    }

    /**
     * Produce a compact readable summary of a page, combining title,
     * description, and the first [maxChars] of body text.
     */
    fun summarize(html: String, maxChars: Int = 2_000): String {
        val meta  = extractMetadata(html)
        val text  = extractText(html, maxChars)
        return buildString {
            appendLine("Title: ${meta.title}")
            if (meta.description.isNotBlank()) appendLine("Summary: ${meta.description}")
            appendLine("Content: ${text.take(maxChars - length - 100)}")
        }.trim()
    }

    /**
     * Heuristically detect the type of page.
     */
    fun detectPageType(html: String): PageType {
        val lower = html.lowercase()
        return when {
            Regex("""<input[^>]+type=["']password["']""").containsMatchIn(lower) -> PageType.LOGIN
            lower.contains("search results") || lower.contains("results for")     -> PageType.SEARCH_RESULTS
            lower.contains("<article") || lower.contains("class=\"article")       -> PageType.ARTICLE
            lower.contains("add to cart") || lower.contains("buy now")            -> PageType.PRODUCT_LISTING
            lower.contains("404") && lower.contains("not found")                  -> PageType.ERROR
            else                                                                   -> PageType.GENERIC
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun attrValue(attrs: String, name: String): String =
        Regex("""${Regex.escape(name)}=["']([^"']*?)["']""", RegexOption.IGNORE_CASE)
            .find(attrs)?.groupValues?.get(1) ?: ""

    private fun resolveUrl(base: String, href: String): String {
        if (base.isBlank()) return href
        return runCatching {
            val baseUri = java.net.URI(base)
            baseUri.resolve(href).toString()
        }.getOrDefault(href)
    }

    private fun String.decodeEntities(): String = this
        .replace("&amp;",  "&")
        .replace("&lt;",   "<")
        .replace("&gt;",   ">")
        .replace("&quot;", "\"")
        .replace("&#39;",  "'")
        .replace("&nbsp;", " ")
        .replace("&apos;", "'")
}
