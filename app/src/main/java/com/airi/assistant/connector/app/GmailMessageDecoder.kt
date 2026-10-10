package com.airi.assistant.connector.app

import org.json.JSONObject
import java.util.Base64

/** Pure payload projection for Gmail read responses; never logs or persists message content. */
internal object GmailMessageDecoder {
    fun decode(payload: JSONObject?): String {
        if (payload == null) return ""
        val candidates = mutableListOf<Pair<String, String>>()
        collect(payload, candidates)
        return candidates.firstOrNull { it.first == "text/plain" }?.second
            ?: candidates.firstOrNull { it.first == "text/html" }?.second?.stripHtml()
            ?: ""
    }

    private fun collect(part: JSONObject, out: MutableList<Pair<String, String>>) {
        val mime = part.optString("mimeType").lowercase()
        val body = part.optJSONObject("body")
        val encoded = body?.optString("data").orEmpty()
        if (encoded.isNotBlank() && (mime == "text/plain" || mime == "text/html")) {
            runCatching {
                val decoded = Base64.getUrlDecoder().decode(encoded).toString(Charsets.UTF_8)
                if (decoded.isNotBlank()) out += mime to decoded
            }
        }
        val parts = part.optJSONArray("parts") ?: return
        for (index in 0 until parts.length()) {
            parts.optJSONObject(index)?.let { collect(it, out) }
        }
    }

    private fun String.stripHtml(): String = replace(Regex("<[^>]+>"), " ")
        .replace(Regex("&nbsp;", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("&amp;", RegexOption.IGNORE_CASE), "&")
        .replace(Regex("\\s+"), " ")
        .trim()
}
