package com.airi.assistant.tools

import java.net.URI
import java.util.Locale

/** URL validation for user-configured N8n webhooks; never formats the full URL for UI/logs. */
object N8nWebhookUrlPolicy {
    sealed interface Validation {
        data class Accepted(val webhook: URI, val healthCheck: URI) : Validation
        data class Rejected(val reason: Reason) : Validation
    }

    enum class Reason { EMPTY, MALFORMED, UNSUPPORTED_SCHEME, MISSING_HOST, USER_INFO, FRAGMENT, INSECURE_REMOTE_HTTP, MISSING_PATH }

    private val localHttpHosts = setOf("localhost", "127.0.0.1", "::1", "10.0.2.2")

    fun validate(raw: String): Validation {
        val uri = runCatching { URI(raw.trim()).normalize() }.getOrNull()
            ?: return Validation.Rejected(if (raw.isBlank()) Reason.EMPTY else Reason.MALFORMED)
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("https", "http")) {
            return Validation.Rejected(Reason.UNSUPPORTED_SCHEME)
        }
        val host = uri.host?.lowercase(Locale.ROOT)
            ?: return Validation.Rejected(Reason.MISSING_HOST)
        if (uri.port == 0 || uri.port > 65_535) return Validation.Rejected(Reason.MALFORMED)
        if (uri.rawUserInfo != null) return Validation.Rejected(Reason.USER_INFO)
        if (uri.rawFragment != null) return Validation.Rejected(Reason.FRAGMENT)
        if (uri.scheme.equals("http", ignoreCase = true) && host !in localHttpHosts) {
            return Validation.Rejected(Reason.INSECURE_REMOTE_HTTP)
        }
        if (uri.rawPath.isNullOrBlank() || uri.rawPath == "/") return Validation.Rejected(Reason.MISSING_PATH)
        return Validation.Accepted(uri, healthCheckUri(uri))
    }

    private fun healthCheckUri(webhook: URI): URI {
        val path = webhook.rawPath.orEmpty()
        val marker = Regex("/webhook(?:-test)?/").find(path)
        val basePath = if (marker != null) path.substring(0, marker.range.first) else path.substringBeforeLast('/', "")
        val healthPath = "${basePath.trimEnd('/')}/healthz".ifBlank { "/healthz" }
        return URI(webhook.scheme, null, webhook.host, webhook.port, healthPath, null, null)
    }
}
