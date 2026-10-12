package com.airi.assistant.ai.skills

import java.net.InetAddress
import java.net.URI

/** Pure, device-independent admission policy for remote/custom skill endpoints. */
internal object DynamicSkillSecurityPolicy {
    private const val MAX_BODY_TEMPLATE_CHARS = 16_384
    private const val MAX_HEADERS = 8
    private val allowedHeaders = setOf("accept", "content-type", "user-agent")
    private val placeholder = Regex("\\{\\{([a-zA-Z][a-zA-Z0-9_.-]{0,63})}}")
    private val privateHost = Regex(
        "^(localhost|.*\\.local|127\\..*|10\\..*|192\\.168\\..*|169\\.254\\..*|172\\.(1[6-9]|2[0-9]|3[0-1])\\..*)$",
        RegexOption.IGNORE_CASE,
    )

    fun validateEndpoint(value: String): Boolean = runCatching {
        val uri = URI(value.trim())
        val host = uri.host ?: return false
        if (!uri.scheme.equals("https", ignoreCase = true)) return false
        if (uri.userInfo != null || uri.port == 80 || uri.port == 21) return false
        if (privateHost.matches(host)) return false
        if (host.matches(Regex("^[0-9.]+$"))) {
            val address = InetAddress.getByName(host)
            if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress) return false
        }
        true
    }.getOrDefault(false)

    fun validateRequestTemplate(bodyTemplate: String, headers: Map<String, String>): Boolean {
        if (bodyTemplate.isBlank() || bodyTemplate.length > MAX_BODY_TEMPLATE_CHARS) return false
        if (headers.size > MAX_HEADERS) return false
        if (headers.keys.any { it.trim().lowercase() !in allowedHeaders }) return false
        if (headers.values.any { value -> value.length > 512 || value.contains("{{secret", ignoreCase = true) }) return false
        val stripped = placeholder.replace(bodyTemplate, "")
        // Only declared input/parameter placeholders are allowed; no arbitrary
        // template expressions, header interpolation, or secret expansion.
        if (stripped.contains("{{") || stripped.contains("}}")) return false
        return placeholder.findAll(bodyTemplate).all { it.groupValues[1] == "input" }
    }
}
