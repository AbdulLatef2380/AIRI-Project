package com.airi.assistant.domain.customskill

import com.airi.assistant.agent.browser.HostAddressPolicy
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object CustomSkillSecurity {
    private val sensitiveNames = listOf("authorization", "token", "secret", "api-key", "apikey", "x-api-key", "key", "password")

    fun isValidEndpoint(endpoint: String): Boolean {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        val host = uri.host
        // C3: a custom skill may only call a PUBLIC host. Private, loopback, link-local and
        // internal names/addresses (in any spelling) are refused, so an imported skill cannot make
        // the phone reach into the local network. The request path checks every resolved
        // address and refuses redirects entirely (see CustomSkillExecutor).
        val queryNames = runCatching {
            uri.rawQuery.orEmpty().split('&').mapNotNull { part ->
                part.substringBefore('=').takeIf(String::isNotBlank)
                    ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8.name()) }
            }
        }.getOrNull() ?: return false
        return scheme == "https" && uri.userInfo == null && uri.port in -1..65535 &&
            !host.isNullOrBlank() && !HostAddressPolicy.isBlockedHost(host) &&
            queryNames.none(::isSensitiveName)
    }

    fun sanitizeUrl(endpoint: String): String {
        val uri = runCatching { URI(endpoint.trim()) }.getOrNull() ?: return "[invalid-url]"
        // Paths and query values can both embed bearer material; logs need only
        // the origin for diagnosing which public service was contacted.
        return "${uri.scheme}://${uri.host.orEmpty()}"
    }

    fun maskHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) -> if (isSensitiveName(key)) "****" else value.take(32) }

    fun isSensitiveName(name: String): Boolean {
        val normalized = name.lowercase().replace('_', '-').replace(' ', '-')
        return sensitiveNames.any { normalized.contains(it) }
    }
}
