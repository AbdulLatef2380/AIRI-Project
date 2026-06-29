package com.airi.assistant.integration

import android.content.SharedPreferences

/**
 * @deprecated Legacy integration using plaintext SharedPreferences.
 *   Replaced by [com.airi.assistant.connector.mcp.NotionMcpConnector] which
 *   stores the PAT via [com.airi.assistant.auth.SecureStorage] backed by
 *   Android Keystore and talks to the Notion REST API. Wiring is in
 *   [com.airi.assistant.connector.ConnectorBootstrap.installDefaults].
 *   Do not add new callers. This class will be deleted in Phase 3.
 */
@Deprecated(
    message = "Use NotionMcpConnector via ConnectorBootstrap. This class uses plaintext SharedPreferences.",
    replaceWith = ReplaceWith(
        "NotionMcpConnector(secureStorage)",
        "com.airi.assistant.connector.mcp.NotionMcpConnector"
    ),
    level = DeprecationLevel.WARNING
)
class NotionIntegration(private val preferences: SharedPreferences) : Integration {
    override val id = "notion"
    override val name = "Notion"
    override val description = "Persist workspace knowledge, notes, and planning context."

    override fun connect() = save(true)

    override fun disconnect() = save(false)

    override fun state(): IntegrationState = IntegrationState(
        id = id,
        name = name,
        description = description,
        isConnected = preferences.getBoolean("${id}_connected", false),
        lastUpdated = preferences.getLong("${id}_updated", 0L)
    )

    private fun save(connected: Boolean) {
        preferences.edit()
            .putBoolean("${id}_connected", connected)
            .putLong("${id}_updated", System.currentTimeMillis())
            .apply()
    }
}
