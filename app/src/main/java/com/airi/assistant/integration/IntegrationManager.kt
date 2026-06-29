package com.airi.assistant.integration

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * @deprecated Legacy integration hub using plaintext SharedPreferences.
 *   All integrations have migrated to the [com.airi.assistant.connector] package:
 *     - GitHub  → [com.airi.assistant.connector.app.GitHubConnector]
 *     - Telegram → [com.airi.assistant.connector.app.TelegramConnector]
 *     - Notion  → [com.airi.assistant.connector.mcp.NotionMcpConnector]
 *   Registration happens in [com.airi.assistant.connector.ConnectorBootstrap].
 *   Connector health is observed via
 *   [com.airi.assistant.connector.ConnectorHealthMonitor].
 *   Do not add new callers. This class will be deleted in Phase 3.
 */
@Deprecated(
    message = "Use ConnectorRegistry + ConnectorBootstrap. All integrations migrated to the connector package.",
    replaceWith = ReplaceWith(
        "ConnectorRegistry",
        "com.airi.assistant.connector.ConnectorRegistry"
    ),
    level = DeprecationLevel.WARNING
)
@Suppress("DEPRECATION")
class IntegrationManager(context: Context) {
    private val preferences = context.getSharedPreferences("airi_integrations", Context.MODE_PRIVATE)
    private val integrations = listOf(
        GithubIntegration(preferences),
        TelegramIntegration(preferences),
        NotionIntegration(preferences)
    )
    private val _states = MutableStateFlow(integrations.map { it.state() })
    val states: StateFlow<List<IntegrationState>> = _states.asStateFlow()

    fun connect(id: String) {
        integrations.firstOrNull { it.id == id }?.connect()
        refresh()
    }

    fun disconnect(id: String) {
        integrations.firstOrNull { it.id == id }?.disconnect()
        refresh()
    }

    fun refresh() {
        _states.value = integrations.map { it.state() }
    }
}
