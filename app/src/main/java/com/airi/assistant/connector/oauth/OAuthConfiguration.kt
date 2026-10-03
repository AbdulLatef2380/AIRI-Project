package com.airi.assistant.connector.oauth

import android.net.Uri
import com.airi.assistant.BuildConfig

sealed interface OAuthConfiguration {
    data class Configured(
        val clientId: String,
        val tenant: String,
        val redirectUri: String,
    ) : OAuthConfiguration

    data object MissingClientId : OAuthConfiguration
    data object InvalidRedirectUri : OAuthConfiguration
    data object DisabledForBuild : OAuthConfiguration
}

object MicrosoftOAuthConfiguration {
    const val CONNECTOR_ID = "microsoft_graph"
    const val REDIRECT_URI = "airi://oauth/callback"
    const val GRAPH_BASE_URL = "https://graph.microsoft.com/v1.0"
    private const val PLACEHOLDER = "MICROSOFT_CLIENT_ID_PLACEHOLDER"

    fun current(): OAuthConfiguration {
        if (!BuildConfig.MICROSOFT_OAUTH_ENABLED) return OAuthConfiguration.DisabledForBuild
        val clientId = BuildConfig.MICROSOFT_CLIENT_ID.trim()
        if (clientId.isBlank() || clientId == PLACEHOLDER) return OAuthConfiguration.MissingClientId
        val uri = runCatching { Uri.parse(REDIRECT_URI) }.getOrNull()
            ?: return OAuthConfiguration.InvalidRedirectUri
        if (uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return OAuthConfiguration.InvalidRedirectUri
        }
        return OAuthConfiguration.Configured(clientId, BuildConfig.MICROSOFT_TENANT.trim().ifBlank { "common" }, REDIRECT_URI)
    }
}
