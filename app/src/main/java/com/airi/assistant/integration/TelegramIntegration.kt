package com.airi.assistant.integration

import android.content.SharedPreferences

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
