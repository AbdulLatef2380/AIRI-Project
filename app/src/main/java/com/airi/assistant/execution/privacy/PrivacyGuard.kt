package com.airi.assistant.execution.privacy

import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.PrivacyLevel

/**
 * Sanitizes cloud-bound execution requests and enforces the user's privacy settings.
 *
 * The guard is the final in-process boundary before a cloud backend receives an
 * [ExecutionRequest]. It must be called for every cloud dispatch, and it cleans every
 * text field adapters can serialize: the prompt, system prompt, and conversation history.
 *
 * In [PrivacyLevel.BALANCED], filesystem paths, content URIs, access tokens, private
 * network addresses, coordinates, device identifiers, and accessibility context are
 * replaced with stable markers. Audit metadata contains category names only; it never
 * includes a matched value. [PrivacyLevel.MAXIMUM] and [ExecutionMode.LOCAL_ONLY]
 * block cloud dispatch entirely. [PrivacyLevel.PERFORMANCE] remains an explicit opt-in
 * to send full context.
 */
object PrivacyGuard {

    /**
     * Returns a request safe for a cloud adapter, or a blocking result when cloud use is
     * disallowed. The returned request, rather than the caller's original request, must be
     * passed to every cloud provider.
     */
    fun evaluate(
        request: ExecutionRequest,
        privacyLevel: PrivacyLevel,
        execMode: ExecutionMode
    ): SanitizationResult {
        if (privacyLevel == PrivacyLevel.MAXIMUM || execMode == ExecutionMode.LOCAL_ONLY) {
            return SanitizationResult.Blocked(
                reason = "PrivacyGuard: mode=$execMode privacy=$privacyLevel — cloud calls blocked"
            )
        }

        if (privacyLevel == PrivacyLevel.PERFORMANCE) {
            return SanitizationResult.Allowed(
                sanitized = request,
                strippedItems = emptyList()
            )
        }

        val stripped = mutableListOf<String>()
        val prompt = sanitizeText(request.prompt, stripped).limitTo(
            MAX_CLOUD_PROMPT_CHARS,
            category = "prompt_truncated",
            stripped = stripped
        )
        val systemPrompt = sanitizeText(request.systemPrompt, stripped).limitTo(
            MAX_CLOUD_SYSTEM_CHARS,
            category = "system_prompt_truncated",
            stripped = stripped
        )
        val history = sanitizeHistory(request.conversationHistory, stripped)

        return SanitizationResult.Allowed(
            sanitized = request.copy(
                prompt = prompt,
                systemPrompt = systemPrompt,
                conversationHistory = history
            ),
            strippedItems = stripped
        )
    }

    /**
     * Produces a compact, user-visible execution-trace summary without retaining
     * credentials, bearer headers, cookies, passwords, filesystem locations, or
     * other context categories handled by this guard. It never exposes the
     * stripped values themselves.
     */
    fun redactForTrace(value: String, maximumChars: Int = 240): String {
        val stripped = mutableListOf<String>()
        return sanitizeText(value, stripped)
            .replaceTracked(TRACE_SECRET_REGEX, "[SECRET_REDACTED]", "trace_secret", stripped)
            .limitTo(maximumChars.coerceIn(1, 1_000), "trace_truncated", stripped)
    }

    /** Redacts trace payload values whose field name itself is sensitive. */
    fun redactTraceField(fieldName: String, value: String): String =
        if (TRACE_SECRET_FIELD_REGEX.containsMatchIn(fieldName)) "[SECRET_REDACTED]"
        else redactForTrace(value)

    private fun sanitizeHistory(
        history: List<ExecutionRequest.ConversationTurn>,
        stripped: MutableList<String>
    ): List<ExecutionRequest.ConversationTurn> {
        var remaining = MAX_CLOUD_HISTORY_CHARS
        val retainedNewestFirst = mutableListOf<ExecutionRequest.ConversationTurn>()
        for (turn in history.asReversed()) {
            if (remaining == 0) {
                stripped += "history_truncated"
                break
            }
            val sanitized = sanitizeText(turn.content, stripped)
            val content = sanitized.limitTo(remaining, "history_truncated", stripped)
            retainedNewestFirst += turn.copy(content = content)
            remaining -= content.length
        }
        return retainedNewestFirst.asReversed()
    }

    private fun sanitizeText(value: String, stripped: MutableList<String>): String {
        var sanitized = value
        sanitized = sanitized.replaceTracked(PATH_REGEX, "[PATH_REDACTED]", "path", stripped)
        sanitized = sanitized.replaceTracked(CONTENT_URI_REGEX, "[URI_REDACTED]", "uri", stripped)
        sanitized = sanitized.replaceTracked(API_KEY_REGEX, "[KEY_REDACTED]", "credential", stripped)
        sanitized = sanitized.replaceTracked(PRIVATE_IP_REGEX, "[IP_REDACTED]", "private_ip", stripped)
        sanitized = sanitized.replaceTracked(GPS_REGEX, "[GPS_REDACTED]", "gps", stripped)
        sanitized = sanitized.replaceTracked(DEVICE_IDENTIFIER_REGEX, "[DEVICE_ID_REDACTED]", "device_id", stripped)
        return sanitized.replaceTracked(
            ACCESSIBILITY_BLOCK_REGEX,
            "[A11Y_REDACTED]",
            "a11y_context",
            stripped
        )
    }

    private fun String.replaceTracked(
        expression: Regex,
        replacement: String,
        category: String,
        stripped: MutableList<String>
    ): String {
        val matches = expression.findAll(this).count()
        if (matches > 0) repeat(matches) { stripped += category }
        return expression.replace(this, replacement)
    }

    private fun String.limitTo(
        maximum: Int,
        category: String,
        stripped: MutableList<String>
    ): String {
        if (length <= maximum) return this
        stripped += category
        return take(maximum) + "\n[…truncated by PrivacyGuard]"
    }

    private val PATH_REGEX = Regex(
        """(?:file://)?(?:/data/|/sdcard/|/storage/emulated/|/cache/|/files/)[^\s'"]{0,120}"""
    )

    private val CONTENT_URI_REGEX = Regex(
        """content://[^\s'"]{0,200}"""
    )

    private val API_KEY_REGEX = Regex(
        """(?:sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z\-_]{35}|ghp_[A-Za-z0-9]{36}|Bearer\s+[A-Za-z0-9\-._~+/]{20,}|api[_\-]?key\s*[:=]\s*['"]?[A-Za-z0-9\-_]{16,})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val TRACE_SECRET_FIELD_REGEX = Regex(
        """(?i)^(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|secret|token|api[_-]?key)$"""
    )

    private val TRACE_SECRET_REGEX = Regex(
        """(?i)(?:authorization|proxy-authorization|cookie|set-cookie|password|passwd|secret|token)\s*[:=]\s*[^\s,;]{1,240}"""
    )

    private val PRIVATE_IP_REGEX = Regex(
        """(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})"""
    )

    private val GPS_REGEX = Regex(
        """[-+]?(?:[1-8]?\d(?:\.\d{4,})|90(?:\.0+)?),\s*[-+]?(?:180(?:\.0+)?|(?:(?:1[0-7]\d)|(?:[1-9]?\d))(?:\.\d{4,}))"""
    )

    private val DEVICE_IDENTIFIER_REGEX = Regex(
        """(?:\b(?:imei|imeisv|serial(?:\s+(?:number|no\.?))?|sn)\s*[:=#]?\s*)(?:[A-Za-z0-9][A-Za-z0-9._-]{5,})""",
        RegexOption.IGNORE_CASE
    )

    private val ACCESSIBILITY_BLOCK_REGEX = Regex(
        """<accessibility_context>.*?</accessibility_context>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private const val MAX_CLOUD_PROMPT_CHARS = 16_000
    private const val MAX_CLOUD_SYSTEM_CHARS = 4_000
    private const val MAX_CLOUD_HISTORY_CHARS = 12_000
}
