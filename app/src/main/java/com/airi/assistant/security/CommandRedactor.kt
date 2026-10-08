package com.airi.assistant.security

/**
 * Redacts credentials from terminal history and activity logs.
 * The executable command itself is never changed; only persisted/displayed text is.
 */
object CommandRedactor {
    private val KEY_VALUE = Regex("(?i)(password|passwd|token|api[_-]?key|secret|authorization)\\s*[=:]\\s*(?!Bearer\\b)([^\\s]+)")
    private val BEARER = Regex("(?i)\\bBearer\\s+[A-Za-z0-9._~+/=-]+")

    fun redact(value: String): String = value
        .replace(BEARER, "Bearer [REDACTED]")
        .replace(KEY_VALUE) { match -> "${match.groupValues[1]}=[REDACTED]" }
}
