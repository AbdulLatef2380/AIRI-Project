package com.airi.assistant.connector.api

/**
 * K1: removes API keys from any text that might be logged or shown (HTTP error bodies,
 * exception messages, URLs). Redacts the exact key and any `key=…` / `x-goog-api-key` pattern,
 * so it still works if the key reaches us in an unexpected form.
 */
object SecretRedactor {
    private val QUERY_KEY = Regex("""([?&]key=)[^&\s"']+""", RegexOption.IGNORE_CASE)
    private val HEADER_KEY = Regex("""(x-goog-api-key["']?\s*[:=]\s*["']?)[^\s"',}]+""", RegexOption.IGNORE_CASE)
    private val GOOGLE_KEY_SHAPE = Regex("""AIza[0-9A-Za-z_\-]{20,}""")

    fun redact(text: String, vararg secrets: String?): String {
        var out = text
        for (secret in secrets) {
            if (!secret.isNullOrBlank() && secret.length >= 8) out = out.replace(secret, "***")
        }
        out = QUERY_KEY.replace(out) { "${it.groupValues[1]}***" }
        out = HEADER_KEY.replace(out) { "${it.groupValues[1]}***" }
        return GOOGLE_KEY_SHAPE.replace(out, "***")
    }
}
