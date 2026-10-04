package com.airi.assistant.connector

/** Provider-side authorization prerequisite attached to one executable action. */
enum class ConnectorProviderGrantKind {
    /** OAuth scope value requested from an authorization server. */
    OAUTH_SCOPE,
    /** GitHub fine-grained PAT repository/organization permission, not an OAuth scope. */
    GITHUB_FINE_GRAINED_TOKEN_PERMISSION,
    /** Notion connection capability and shared-resource access. */
    NOTION_CONNECTION_CAPABILITY,
    /** Telegram Bot API token and bot/chat reachability; Telegram has no OAuth scopes here. */
    TELEGRAM_BOT_TOKEN_CAPABILITY,
    /** Possession/authorization of a provider-issued webhook URL. */
    WEBHOOK_ENDPOINT_AUTHORITY,
    /** Provider permission needs endpoint-specific verification; do not guess a scope. */
    PROVIDER_ENDPOINT_PERMISSION,
}

data class ConnectorProviderGrant(
    val kind: ConnectorProviderGrantKind,
    val value: String,
)
