package com.airi.assistant.connector

/**
 * Provider-independent authentication modes used by the connector UI.
 * A connector declares the mode; the UI never contains provider-specific
 * if/else branches for deciding how a connection starts.
 */
enum class ConnectorAuthMode {
    OAUTH2_PKCE,
    API_KEY,
    PERSONAL_ACCESS_TOKEN,
    DEVICE_CODE,
    LOCAL_PERMISSION,
    MCP_CONFIGURATION,
    WEBHOOK,
    NONE,
    COMING_SOON,
}

data class ConnectorAuthStrategy(
    val connectorId: String,
    val runtimeId: String,
    val mode: ConnectorAuthMode,
    val provider: String,
    val requiredScopes: List<String> = emptyList(),
    val credentialLabel: String? = null,
    val redirectUri: String? = null,
    val publicClientOnly: Boolean = mode == ConnectorAuthMode.OAUTH2_PKCE,
    val officialAuthorizationRequired: Boolean = mode in setOf(
        ConnectorAuthMode.OAUTH2_PKCE,
        ConnectorAuthMode.API_KEY,
        ConnectorAuthMode.PERSONAL_ACCESS_TOKEN,
        ConnectorAuthMode.DEVICE_CODE,
        ConnectorAuthMode.MCP_CONFIGURATION,
        ConnectorAuthMode.WEBHOOK,
    ),
) {
    val isExecutable: Boolean
        get() = mode != ConnectorAuthMode.COMING_SOON

    val summary: String
        get() = when (mode) {
            ConnectorAuthMode.OAUTH2_PKCE -> "Authorize through the official $provider sign-in page."
            ConnectorAuthMode.API_KEY -> "Provide the user's $provider API key through secure storage."
            ConnectorAuthMode.PERSONAL_ACCESS_TOKEN -> "Provide the user's $provider personal access token through secure storage."
            ConnectorAuthMode.DEVICE_CODE -> "Show the provider device code and verification page."
            ConnectorAuthMode.LOCAL_PERMISSION -> "Request the Android permission at the moment of use."
            ConnectorAuthMode.MCP_CONFIGURATION -> "Configure and authorize the selected MCP server."
            ConnectorAuthMode.WEBHOOK -> "Configure the user's webhook endpoint and secret."
            ConnectorAuthMode.NONE -> "No user authentication is required."
            ConnectorAuthMode.COMING_SOON -> "This connector is catalogued but has no executable adapter yet."
        }

    val steps: List<String>
        get() = when (mode) {
            ConnectorAuthMode.OAUTH2_PKCE -> listOf(
                "Create state and PKCE verifier",
                "Open the provider authorization page",
                "Validate the redirect state and code",
                "Exchange the code without embedding a client secret",
                "Store the user's token securely",
                "Verify capabilities before Connected",
            )
            ConnectorAuthMode.API_KEY,
            ConnectorAuthMode.PERSONAL_ACCESS_TOKEN,
            ConnectorAuthMode.WEBHOOK -> listOf(
                "Collect the user's credential only in the AIRI secure form",
                "Store it in encrypted storage",
                "Validate it with a safe provider request",
                "Expose tools only after a healthy result",
            )
            ConnectorAuthMode.DEVICE_CODE -> listOf(
                "Request a provider device code",
                "Show the code and verification URL",
                "Poll until authorization completes",
                "Store and verify the resulting token",
            )
            ConnectorAuthMode.LOCAL_PERMISSION -> listOf(
                "Request the Android permission",
                "Verify the permission result",
                "Run a safe local health check",
            )
            ConnectorAuthMode.MCP_CONFIGURATION -> listOf(
                "Validate the MCP endpoint",
                "Complete the server's declared authorization",
                "Discover tools after authorization",
            )
            ConnectorAuthMode.NONE -> listOf("Run a local health check")
            ConnectorAuthMode.COMING_SOON -> listOf("Wait for an official AIRI adapter")
        }
}

/** Single resolver used by catalog, details screen, and connect actions. */
object ConnectorAuthStrategies {
    fun forMeta(meta: ConnectorMeta): ConnectorAuthStrategy {
        val definition = OfficialConnectorCatalog.get(meta.id)
        val auth = definition?.authenticationType ?: meta.authenticationType
        val runtimeId = meta.runtimeId
        if (definition?.status == ConnectorAvailability.COMING_SOON) {
            return ConnectorAuthStrategy(
                connectorId = meta.id,
                runtimeId = runtimeId,
                mode = ConnectorAuthMode.COMING_SOON,
                provider = definition.provider,
            )
        }
        val mode = when {
            // These are the currently implemented AIRI adapters. Their existing
            // screens remain the provider-specific executors of this plan.
            runtimeId == "github" -> ConnectorAuthMode.PERSONAL_ACCESS_TOKEN
            runtimeId == "telegram" -> ConnectorAuthMode.API_KEY
            runtimeId == "google" -> ConnectorAuthMode.OAUTH2_PKCE
            runtimeId == "microsoft_graph" -> ConnectorAuthMode.OAUTH2_PKCE
            runtimeId == "zapier" -> ConnectorAuthMode.OAUTH2_PKCE
            runtimeId == "notion" || runtimeId == "notion_mcp" -> ConnectorAuthMode.MCP_CONFIGURATION
            auth == ConnectorAuthenticationType.OAUTH2 || auth == ConnectorAuthenticationType.OAUTH2_AND_API -> ConnectorAuthMode.OAUTH2_PKCE
            auth == ConnectorAuthenticationType.API_KEY -> ConnectorAuthMode.API_KEY
            auth == ConnectorAuthenticationType.PERSONAL_ACCESS_TOKEN -> ConnectorAuthMode.PERSONAL_ACCESS_TOKEN
            auth == ConnectorAuthenticationType.MCP -> ConnectorAuthMode.MCP_CONFIGURATION
            auth == ConnectorAuthenticationType.WEBHOOK -> ConnectorAuthMode.WEBHOOK
            auth == ConnectorAuthenticationType.LOCAL -> ConnectorAuthMode.LOCAL_PERMISSION
            else -> when (meta.type) {
                ConnectorType.LOCAL, ConnectorType.SYSTEM -> ConnectorAuthMode.LOCAL_PERMISSION
                else -> ConnectorAuthMode.NONE
            }
        }
        val provider = definition?.provider ?: meta.provider ?: meta.name
        val scopes = definition?.requiredScopes.orEmpty()
        return ConnectorAuthStrategy(
            connectorId = meta.id,
            runtimeId = runtimeId,
            mode = mode,
            provider = provider,
            requiredScopes = scopes,
            credentialLabel = when (mode) {
                ConnectorAuthMode.API_KEY -> "$provider API key"
                ConnectorAuthMode.PERSONAL_ACCESS_TOKEN -> "$provider personal access token"
                else -> null
            },
            redirectUri = if (mode == ConnectorAuthMode.OAUTH2_PKCE) "airi://oauth/callback" else null,
        )
    }
}
