package com.airi.assistant.marketplace

import com.airi.assistant.agent.browser.HostAddressPolicy
import java.net.URI

/**
 * Report 3, M1: pure checks applied before a marketplace skill definition is
 * downloaded or its endpoint registered. Catalog data is third-party input, so
 * the definition URL gets the same treatment as any other fetched URL (S5/S6).
 */
object MarketplaceInstallPolicy {

    /** Upper bound for a skill definition download. */
    const val MAX_DEFINITION_BYTES = 256 * 1024L
    const val MAX_REDIRECTS = 3

    sealed class UrlDecision {
        data class Allowed(val normalizedUrl: String, val host: String) : UrlDecision()
        data class Rejected(val reason: String) : UrlDecision()
    }

    /** HTTPS only, no credentials in the URL, and a public host. */
    fun checkHttpsPublicUrl(value: String): UrlDecision {
        val uri = runCatching { URI(value.trim()) }.getOrNull()
            ?: return UrlDecision.Rejected("The URL is not valid")
        if (!uri.scheme.equals("https", ignoreCase = true)) {
            return UrlDecision.Rejected("Only HTTPS addresses are accepted")
        }
        if (uri.userInfo != null) return UrlDecision.Rejected("Credentials in the URL are not accepted")
        if (uri.port !in -1..65535) return UrlDecision.Rejected("The port is not valid")
        val host = uri.host?.lowercase()?.trimEnd('.')
            ?: return UrlDecision.Rejected("The URL has no valid host")
        if (HostAddressPolicy.isBlockedHost(host)) {
            return UrlDecision.Rejected("The host is not a public address")
        }
        return UrlDecision.Allowed(uri.normalize().toString(), host)
    }

    /** Hosts shown to the user for confirmation: the endpoint a skill will send input to. */
    fun endpointHost(endpoint: String): String? =
        (checkHttpsPublicUrl(endpoint) as? UrlDecision.Allowed)?.host
}
