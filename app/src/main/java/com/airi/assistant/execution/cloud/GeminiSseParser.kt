package com.airi.assistant.execution.cloud

import org.json.JSONObject

/**
 * Parses one Gemini SSE data payload without relying on JSON whitespace or key order.
 * Gemini may serialize the same response as `"finishReason":"STOP"` or
 * `"finishReason": "STOP"`; both are valid and must be treated identically.
 */
internal object GeminiSseParser {
    data class Event(
        val text: String = "",
        val terminal: Boolean = false,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val hasError: Boolean = false,
        val errorCode: String? = null,
        val errorStatus: String? = null,
        val finishReason: String? = null,
        val malformed: Boolean = false,
    )

    fun parse(payload: String): Event {
        val root = runCatching { JSONObject(payload) }.getOrNull()
            ?: return Event(malformed = true)
        if (root.has("error")) {
            val error = root.optJSONObject("error") ?: return Event(hasError = true, malformed = true)
            return Event(
                hasError = true,
                errorCode = error.optStringValue("code"),
                errorStatus = error.optStringValue("status"),
            )
        }

        val candidates = root.optJSONArray("candidates")
        val text = buildString {
            if (candidates != null) {
                for (i in 0 until candidates.length()) {
                    val candidate = candidates.optJSONObject(i) ?: continue
                    val content = candidate.optJSONObject("content")
                    val parts = content?.optJSONArray("parts") ?: continue
                    for (j in 0 until parts.length()) {
                        val part = parts.optJSONObject(j) ?: continue
                        append(part.optString("text", ""))
                    }
                }
            }
        }

        val finishReason = candidates?.let { values ->
            (0 until values.length()).asSequence()
                .mapNotNull { values.optJSONObject(it)?.optStringValue("finishReason") }
                .firstOrNull()
        }
        val promptBlockReason = root.optJSONObject("promptFeedback")?.optStringValue("blockReason")
        val usage = root.optJSONObject("usageMetadata")
        return Event(
            text = text,
            terminal = !finishReason.isNullOrBlank(),
            promptTokens = usage?.optIntOrNull("promptTokenCount"),
            completionTokens = usage?.optIntOrNull("candidatesTokenCount"),
            hasError = !promptBlockReason.isNullOrBlank(),
            errorStatus = promptBlockReason,
            finishReason = finishReason,
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (has(name) && !isNull(name)) optInt(name) else null

    private fun JSONObject.optStringValue(name: String): String? =
        opt(name)?.takeUnless { it === JSONObject.NULL }?.toString()?.takeIf(String::isNotBlank)
}
