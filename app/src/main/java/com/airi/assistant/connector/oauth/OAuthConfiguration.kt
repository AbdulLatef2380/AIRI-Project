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

/** Dropbox public-client OAuth configuration. PKCE means no client secret is shipped. */
object DropboxOAuthConfiguration {
    const val CONNECTOR_ID = "dropbox"
    const val REDIRECT_URI = "airi://oauth/callback"
    const val AUTHORIZE_URL = "https://www.dropbox.com/oauth2/authorize"
    const val TOKEN_URL = "https://api.dropboxapi.com/oauth2/token"
    const val API_BASE_URL = "https://api.dropboxapi.com/2"
    private const val PLACEHOLDER = "DROPBOX_CLIENT_ID_PLACEHOLDER"

    fun current(): OAuthConfiguration {
        return when {
            BuildConfig.DROPBOX_CLIENT_ID.trim().isBlank() ||
                BuildConfig.DROPBOX_CLIENT_ID.trim() == PLACEHOLDER -> OAuthConfiguration.MissingClientId
            else -> {
                val uri = runCatching { Uri.parse(REDIRECT_URI) }.getOrNull()
                if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
                    OAuthConfiguration.InvalidRedirectUri
                } else {
                    OAuthConfiguration.Configured(BuildConfig.DROPBOX_CLIENT_ID.trim(), "", REDIRECT_URI)
                }
            }
        }
    }
}

/** Box OAuth 2.0 is confidential; code exchange is delegated to AIRI's broker. */
object BoxOAuthConfiguration {
    const val CONNECTOR_ID = "box"
    const val REDIRECT_URI = "airi://oauth/callback"
    const val AUTHORIZE_URL = "https://account.box.com/api/oauth2/authorize"
    const val API_BASE_URL = "https://api.box.com/2.0"
    const val TOKEN_BROKER_PATH = "/v1/connectors/box/oauth/callback"

    fun current(): OAuthConfiguration {
        val clientId = BuildConfig.BOX_CLIENT_ID.trim()
        val broker = BuildConfig.BOX_OAUTH_BROKER_URL.trim()
        if (clientId.isBlank() || broker.isBlank()) return OAuthConfiguration.MissingClientId
        val uri = runCatching { Uri.parse(REDIRECT_URI) }.getOrNull()
        if (uri == null || uri.scheme.isNullOrBlank() || uri.host.isNullOrBlank()) {
            return OAuthConfiguration.InvalidRedirectUri
        }
        val brokerUri = runCatching { Uri.parse(broker) }.getOrNull()
        if (brokerUri?.scheme != "https" || brokerUri.host.isNullOrBlank()) {
            return OAuthConfiguration.InvalidRedirectUri
        }
        return OAuthConfiguration.Configured(clientId, broker, REDIRECT_URI)
    }
}
