package com.airi.assistant.integration

import android.content.SharedPreferences

/**
 * @deprecated Legacy integration using plaintext SharedPreferences.
 *   Replaced by [com.airi.assistant.connector.app.TelegramConnector] which
 *   stores credentials via [com.airi.assistant.auth.SecureStorage] backed by
 *   Android Keystore. Wiring is in
 *   [com.airi.assistant.connector.ConnectorBootstrap.installDefaults].
 *   Do not add new callers. This class will be deleted in Phase 3.
 */
@Deprecated(
    message = "Use TelegramConnector via ConnectorBootstrap. This class uses plaintext SharedPreferences.",
    replaceWith = ReplaceWith(
        "TelegramConnector(secureStorage)",
        "com.airi.assistant.connector.app.TelegramConnector"
    ),
    level = DeprecationLevel.WARNING
)
class TelegramIntegration(private val preferences: SharedPreferences) : Integration {
    override val id = "telegram"
    override val name = "Telegram"
    override val description = "Link Telegram automation state and assistant messaging workflows."

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
