package com.airi.assistant.integration

import android.content.SharedPreferences

/**
 * @deprecated Legacy integration using plaintext SharedPreferences.
 *   Replaced by [com.airi.assistant.connector.app.GitHubConnector] which
 *   uses [com.airi.assistant.connector.ConnectorAuthManager] backed by
 *   EncryptedSharedPreferences. Wiring is in
 *   [com.airi.assistant.connector.ConnectorBootstrap.installDefaults].
 *   Do not add new callers. This class will be deleted in Phase 3.
 */
@Deprecated(
    message = "Use GitHubConnector via ConnectorBootstrap. This class uses plaintext SharedPreferences.",
    replaceWith = ReplaceWith(
        "GitHubConnector(authManager)",
        "com.airi.assistant.connector.app.GitHubConnector"
    ),
    level = DeprecationLevel.WARNING
)
class GithubIntegration(private val preferences: SharedPreferences) : Integration {
    override val id = "github"
    override val name = "GitHub"
    override val description = "Connect repositories, issues, and coding context to AIRI."

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
