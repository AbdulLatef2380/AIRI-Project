package com.airi.assistant.connector

/** The seven catalog surfaces that form the first production connector gate. */
data class PrimaryConnectorContract(
    val catalogId: String,
    val runtimeId: String,
    val authMode: ConnectorAuthMode,
    val requiresToolExposure: Boolean = true,
    val requiresHealthyRuntime: Boolean = true,
)

object PrimaryConnectorContracts {
    val all: List<PrimaryConnectorContract> = listOf(
        PrimaryConnectorContract("google_gmail", "google", ConnectorAuthMode.OAUTH2_PKCE),
        PrimaryConnectorContract("google_calendar", "google", ConnectorAuthMode.OAUTH2_PKCE),
        PrimaryConnectorContract("google_drive", "google", ConnectorAuthMode.OAUTH2_PKCE),
        PrimaryConnectorContract("github", "github", ConnectorAuthMode.PERSONAL_ACCESS_TOKEN),
        PrimaryConnectorContract("telegram", "telegram", ConnectorAuthMode.API_KEY),
        PrimaryConnectorContract("notion", "notion_mcp", ConnectorAuthMode.MCP_CONFIGURATION),
        PrimaryConnectorContract("zapier", "zapier", ConnectorAuthMode.OAUTH2_CONFIDENTIAL),
    )

    fun forCatalogId(id: String): PrimaryConnectorContract? = all.firstOrNull { it.catalogId == id }
}
