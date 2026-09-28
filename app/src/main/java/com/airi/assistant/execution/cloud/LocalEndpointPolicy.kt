package com.airi.assistant.execution.cloud

import java.net.URI
import java.util.Locale

/** Validation shared by local-provider settings and the OpenAI-compatible executor. */
object LocalEndpointPolicy {
    private val cleartextLocalHosts = setOf("localhost", "127.0.0.1", "10.0.2.2")

    fun normalizeAllowedEndpoint(raw: String, allowLocalCleartext: Boolean = com.airi.assistant.BuildConfig.DEBUG): String? {
        val candidate = raw.trim()
        if (candidate.isBlank()) return null
        val uri = runCatching { URI(candidate) }.getOrNull() ?: return null
        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        if (uri.userInfo != null || uri.query != null || uri.fragment != null) return null
        val allowed = when (scheme) {
            "https" -> true
            "http" -> allowLocalCleartext && (host in cleartextLocalHosts || host.endsWith(".local"))
            else -> false
        }
        if (!allowed) return null
        return uri.toASCIIString().trimEnd('/')
    }

    fun isAllowed(raw: String, allowLocalCleartext: Boolean = com.airi.assistant.BuildConfig.DEBUG): Boolean =
        normalizeAllowedEndpoint(raw, allowLocalCleartext) != null
}
