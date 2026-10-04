package com.airi.assistant.connector

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists only user-selected access tiers (never provider credentials).
 * Unset, malformed, or unknown values fail closed as NOT_CONFIGURED.
 */
class SharedPreferencesConnectorAccessProfileStore(context: Context) : ConnectorAccessProfileStore {
    private val preferences: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Synchronized
    override fun get(surfaceId: String): ConnectorAccessProfile {
        if (surfaceId.isBlank()) return ConnectorAccessProfile.NOT_CONFIGURED
        val raw = preferences.getString(key(surfaceId), null) ?: return ConnectorAccessProfile.NOT_CONFIGURED
        return runCatching { ConnectorAccessProfile.valueOf(raw) }
            .getOrDefault(ConnectorAccessProfile.NOT_CONFIGURED)
    }

    @Synchronized
    override fun set(surfaceId: String, profile: ConnectorAccessProfile) {
        require(surfaceId.isNotBlank()) { "surfaceId must not be blank" }
        val editor = preferences.edit()
        if (profile == ConnectorAccessProfile.NOT_CONFIGURED) editor.remove(key(surfaceId))
        else editor.putString(key(surfaceId), profile.name)
        check(editor.commit()) { "Could not persist connector access profile" }
    }

    @Synchronized
    override fun all(): Map<String, ConnectorAccessProfile> = preferences.all.mapNotNull { (storedKey, value) ->
        if (!storedKey.startsWith(KEY_PREFIX)) return@mapNotNull null
        val surfaceId = storedKey.removePrefix(KEY_PREFIX).takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val profile = (value as? String)?.let { raw ->
            runCatching { ConnectorAccessProfile.valueOf(raw) }.getOrNull()
        }?.takeIf { it != ConnectorAccessProfile.NOT_CONFIGURED } ?: return@mapNotNull null
        surfaceId to profile
    }.toMap()

    @Synchronized
    override fun clear() {
        check(preferences.edit().clear().commit()) { "Could not clear connector access profiles" }
    }

    private fun key(surfaceId: String) = KEY_PREFIX + surfaceId

    private companion object {
        const val PREFERENCES_NAME = "airi_connector_access_profiles"
        const val KEY_PREFIX = "profile:"
    }
}
