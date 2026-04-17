package com.airi.assistant.domain.customskill

import android.net.Uri

object CustomSkillSecurity {
    private val sensitiveNames = listOf("authorization", "token", "secret", "api-key", "apikey", "x-api-key", "key", "password")

    fun isValidEndpoint(endpoint: String): Boolean {
        val uri = runCatching { Uri.parse(endpoint.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return scheme in setOf("https", "http") && !uri.host.isNullOrBlank()
    }

    fun sanitizeUrl(endpoint: String): String {
        val uri = runCatching { Uri.parse(endpoint.trim()) }.getOrNull() ?: return "[invalid-url]"
        val base = "${uri.scheme}://${uri.host.orEmpty()}${uri.encodedPath.orEmpty()}"
        val query = uri.queryParameterNames.takeIf { it.isNotEmpty() }?.joinToString("&") { name ->
            val value = if (isSensitiveName(name)) "****" else uri.getQueryParameter(name).orEmpty().take(32)
            "$name=$value"
        }
        return if (query.isNullOrBlank()) base else "$base?$query"
    }

    fun maskHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (key, value) -> if (isSensitiveName(key)) "****" else value.take(32) }

    fun isSensitiveName(name: String): Boolean {
        val normalized = name.lowercase()
        return sensitiveNames.any { normalized.contains(it) }
    }
}