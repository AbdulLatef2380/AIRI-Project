package com.airi.assistant.security

/**
 * Serializes attacker-controlled text as data, not as prompt instructions.
 * This is a prompt-boundary aid, not a model-level security boundary: tool
 * permissions, project scope, approvals, and secret access must still be
 * enforced by runtime policy independently of model compliance.
 */
object UntrustedContentEnvelope {
    private const val MAX_SOURCE_CHARS = 80
    private const val MAX_CONTENT_CHARS = 12_000

    fun wrap(source: String, content: String, maxChars: Int = 4_000): String {
        if (maxChars <= 0 || content.isEmpty()) return ""
        val safeSource = source
            .filter { it.isLetterOrDigit() || it in setOf('_', '-', '.', ':') }
            .take(MAX_SOURCE_CHARS)
            .ifBlank { "external" }
        val bounded = redactCredentials(
            content.replace("\u0000", "").take(maxChars.coerceAtMost(MAX_CONTENT_CHARS))
        )
        return """UNTRUSTED_DATA_JSON
{"source":${quote(safeSource)},"content":${quote(bounded)}}
END_UNTRUSTED_DATA_JSON"""
    }

    /** Redacts known credential forms without adding a prompt envelope. */
    fun redactSensitiveValues(value: String): String =
        redactCredentials(value.replace("\u0000", ""))

    private fun quote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun redactCredentials(value: String): String {
        var safe = value.replace(BEARER, "Bearer [REDACTED]")
            .replace(PROVIDER_KEY, "[REDACTED_CREDENTIAL]")
            .replace(JWT, "[REDACTED_CREDENTIAL]")
            .replace(URL_KEY) { "${it.groupValues[1]}[REDACTED]" }
        safe = SECRET_ASSIGNMENT.replace(safe) { "${it.groupValues[1]}[REDACTED]" }
        return safe
    }

    private val BEARER = Regex("(?i)\\b(?:Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]{8,}")
    private val PROVIDER_KEY = Regex(
        "(?i)(?:sk-(?:proj-)?[A-Za-z0-9_-]{8,}|sk-ant-[A-Za-z0-9_-]{8,}|AIza[0-9A-Za-z_-]{20,}|(?:ghp|gho|ghu|ghs|ghr)_[A-Za-z0-9]{12,}|github_pat_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{8,}|AKIA[0-9A-Z]{16})"
    )
    private val JWT = Regex("\\beyJ[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\.[A-Za-z0-9_-]{8,}\\b")
    private val URL_KEY = Regex("(?i)([?&](?:key|api[_-]?key|access[_-]?token|token)=)[^&\\s\"']+")
    private val SECRET_ASSIGNMENT = Regex(
        "(?i)([\\\"']?(?:api(?:[_-]|\\s)?key|access[_-]?token|refresh[_-]?token|client[_-]?secret|token|secret|password|passwd|credential|webhook[_-]?key)[\\\"']?\\s*(?:[:=]|\\bis\\b)\\s*[\\\"']?)[^\\\"'\\n,;}{]+"
    )
}
