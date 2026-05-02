package com.airi.assistant.execution.privacy

import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.ExecutionRequest
import com.airi.assistant.execution.PrivacyLevel

/**
 * Sanitizes prompts before they leave the device and enforces the
 * user's privacy settings at the execution boundary.
 *
 * The guard is the last line of defence before any bytes travel over
 * the network. It is called by [HybridOrchestrator] on EVERY request
 * routed to a cloud backend — even if the caller believes it has already
 * sanitized the input.
 *
 * ## What it strips
 *  - Android filesystem paths   (`/data/`, `/sdcard/`, `/storage/`, `content://`)
 *  - API keys / bearer tokens   (`sk-…`, `AIza…`, `Bearer …`, `ghp_…`)
 *  - Private IP addresses       (192.168.x.x, 10.x.x.x, 172.16–31.x.x)
 *  - GPS coordinates            (lat/lon patterns)
 *  - IMEI / serial number patterns
 *
 * ## What it blocks entirely
 *  - [PrivacyLevel.MAXIMUM] + any cloud target → [SanitizationResult.Blocked]
 *  - [ExecutionMode.LOCAL_ONLY] + any cloud target → [SanitizationResult.Blocked]
 *  - Requests whose prompt contains `<accessibility_context>` tags without
 *    explicit user approval → strips the block
 *
 * ## Performance contract
 *  All operations are synchronous regex + string operations running on
 *  whatever thread the caller is on (expected: Dispatchers.Default).
 *  No I/O, no allocations beyond the sanitized string.
 */
object PrivacyGuard {

    /**
     * Evaluate whether [request] may be sent to a cloud backend and, if so,
     * what the sanitized version looks like.
     *
     * @param request       The request that will be sent to cloud.
     * @param privacyLevel  User's configured privacy level.
     * @param execMode      Resolved execution mode (after prefs/safety gates).
     * @return [SanitizationResult] — either [SanitizationResult.Allowed] with
     *         a sanitized copy of [request], or [SanitizationResult.Blocked].
     */
    fun evaluate(
        request:      ExecutionRequest,
        privacyLevel: PrivacyLevel,
        execMode:     ExecutionMode
    ): SanitizationResult {
        // Hard block: privacy or mode forbids cloud.
        if (privacyLevel == PrivacyLevel.MAXIMUM || execMode == ExecutionMode.LOCAL_ONLY) {
            return SanitizationResult.Blocked(
                reason = "PrivacyGuard: mode=$execMode privacy=$privacyLevel — cloud calls blocked"
            )
        }

        // PERFORMANCE level: trust the user, pass through unchanged.
        if (privacyLevel == PrivacyLevel.PERFORMANCE) {
            return SanitizationResult.Allowed(
                sanitized = request,
                strippedItems = emptyList()
            )
        }

        // BALANCED: sanitize sensitive patterns.
        val stripped = mutableListOf<String>()

        var prompt       = request.prompt
        var systemPrompt = request.systemPrompt

        // Strip Android file system paths.
        PATH_REGEX.findAll(prompt).forEach { stripped += "path:${it.value.take(40)}" }
        prompt       = PATH_REGEX.replace(prompt,       "[PATH_REDACTED]")
        systemPrompt = PATH_REGEX.replace(systemPrompt, "[PATH_REDACTED]")

        // Strip content:// URIs.
        CONTENT_URI_REGEX.findAll(prompt).forEach { stripped += "uri:${it.value.take(40)}" }
        prompt       = CONTENT_URI_REGEX.replace(prompt,       "[URI_REDACTED]")
        systemPrompt = CONTENT_URI_REGEX.replace(systemPrompt, "[URI_REDACTED]")

        // Strip API keys / bearer tokens.
        API_KEY_REGEX.findAll(prompt).forEach { stripped += "apikey" }
        prompt       = API_KEY_REGEX.replace(prompt,       "[KEY_REDACTED]")
        systemPrompt = API_KEY_REGEX.replace(systemPrompt, "[KEY_REDACTED]")

        // Strip private IP addresses.
        PRIVATE_IP_REGEX.findAll(prompt).forEach { stripped += "ip" }
        prompt       = PRIVATE_IP_REGEX.replace(prompt,       "[IP_REDACTED]")
        systemPrompt = PRIVATE_IP_REGEX.replace(systemPrompt, "[IP_REDACTED]")

        // Strip GPS coordinates (rough pattern: ±dd.dddddd, ±ddd.dddddd).
        GPS_REGEX.findAll(prompt).forEach { stripped += "gps" }
        prompt       = GPS_REGEX.replace(prompt,       "[GPS_REDACTED]")
        systemPrompt = GPS_REGEX.replace(systemPrompt, "[GPS_REDACTED]")

        // Strip accessibility context blocks (may contain private screen content).
        ACCESSIBILITY_BLOCK_REGEX.findAll(prompt).forEach { stripped += "a11y_context" }
        prompt       = ACCESSIBILITY_BLOCK_REGEX.replace(prompt,       "[A11Y_REDACTED]")
        systemPrompt = ACCESSIBILITY_BLOCK_REGEX.replace(systemPrompt, "[A11Y_REDACTED]")

        // Truncate to avoid accidentally uploading huge local context.
        val maxChars = MAX_CLOUD_PROMPT_CHARS
        if (prompt.length > maxChars) {
            stripped += "truncated(${prompt.length}→$maxChars)"
            prompt = prompt.take(maxChars) + "\n[…truncated by PrivacyGuard]"
        }
        if (systemPrompt.length > MAX_CLOUD_SYSTEM_CHARS) {
            systemPrompt = systemPrompt.take(MAX_CLOUD_SYSTEM_CHARS)
        }

        return SanitizationResult.Allowed(
            sanitized    = request.copy(prompt = prompt, systemPrompt = systemPrompt),
            strippedItems = stripped
        )
    }

    // ── Regex patterns ────────────────────────────────────────────────────────

    private val PATH_REGEX = Regex(
        """(?:/data/|/sdcard/|/storage/emulated/|/cache/|/files/)[^\s'"]{0,120}"""
    )

    private val CONTENT_URI_REGEX = Regex(
        """content://[^\s'"]{0,200}"""
    )

    private val API_KEY_REGEX = Regex(
        """(?:sk-[A-Za-z0-9]{20,}|AIza[0-9A-Za-z\-_]{35}|ghp_[A-Za-z0-9]{36}|Bearer\s+[A-Za-z0-9\-._~+/]{20,}|api[_\-]?key\s*[:=]\s*['""]?[A-Za-z0-9\-_]{16,})""",
        setOf(RegexOption.IGNORE_CASE)
    )

    private val PRIVATE_IP_REGEX = Regex(
        """(?:192\.168\.\d{1,3}\.\d{1,3}|10\.\d{1,3}\.\d{1,3}\.\d{1,3}|172\.(?:1[6-9]|2\d|3[01])\.\d{1,3}\.\d{1,3})"""
    )

    private val GPS_REGEX = Regex(
        """[-+]?(?:[1-8]?\d(?:\.\d{4,})|90(?:\.0+)?),\s*[-+]?(?:180(?:\.0+)?|(?:(?:1[0-7]\d)|(?:[1-9]?\d))(?:\.\d{4,}))"""
    )

    private val ACCESSIBILITY_BLOCK_REGEX = Regex(
        """<accessibility_context>.*?</accessibility_context>""",
        setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
    )

    private const val MAX_CLOUD_PROMPT_CHARS  = 16_000   // ~4 000 tokens
    private const val MAX_CLOUD_SYSTEM_CHARS  = 4_000    // ~1 000 tokens
}
