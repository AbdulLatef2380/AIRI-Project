package com.airi.assistant.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

class SecureStorage(context: Context) {

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "airi_secure_integrations",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        context.getSharedPreferences("airi_secure_integrations_fallback", Context.MODE_PRIVATE)
    }

    // ─── GitHub ────────────────────────────────────────────────────────────────

    fun saveGithubToken(token: String) =
        prefs.edit().putString(KEY_GITHUB_TOKEN, token).apply()

    fun getGithubToken(): String? = prefs.getString(KEY_GITHUB_TOKEN, null)

    fun saveGithubConnected(connected: Boolean, username: String = "") {
        prefs.edit()
            .putBoolean(KEY_GITHUB_CONNECTED, connected)
            .putString(KEY_GITHUB_USERNAME, username)
            .putLong(KEY_GITHUB_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun isGithubConnected(): Boolean = prefs.getBoolean(KEY_GITHUB_CONNECTED, false)
    fun getGithubUsername(): String = prefs.getString(KEY_GITHUB_USERNAME, "") ?: ""
    fun getGithubUpdated(): Long = prefs.getLong(KEY_GITHUB_UPDATED, 0L)

    // ─── Telegram ──────────────────────────────────────────────────────────────

    fun saveTelegramToken(token: String) =
        prefs.edit().putString(KEY_TELEGRAM_TOKEN, token).apply()

    fun getTelegramToken(): String? = prefs.getString(KEY_TELEGRAM_TOKEN, null)

    fun saveTelegramConnected(connected: Boolean, username: String = "") {
        prefs.edit()
            .putBoolean(KEY_TELEGRAM_CONNECTED, connected)
            .putString(KEY_TELEGRAM_USERNAME, username)
            .putLong(KEY_TELEGRAM_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun isTelegramConnected(): Boolean = prefs.getBoolean(KEY_TELEGRAM_CONNECTED, false)
    fun getTelegramUsername(): String = prefs.getString(KEY_TELEGRAM_USERNAME, "") ?: ""
    fun getTelegramUpdated(): Long = prefs.getLong(KEY_TELEGRAM_UPDATED, 0L)

    // ─── Google ────────────────────────────────────────────────────────────────

    fun saveGoogleConnected(connected: Boolean, email: String = "") {
        prefs.edit()
            .putBoolean(KEY_GOOGLE_CONNECTED, connected)
            .putString(KEY_GOOGLE_EMAIL, email)
            .putLong(KEY_GOOGLE_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun saveGoogleIdToken(token: String) =
        prefs.edit().putString(KEY_GOOGLE_ID_TOKEN, token).apply()

    fun getGoogleIdToken(): String? = prefs.getString(KEY_GOOGLE_ID_TOKEN, null)
    fun isGoogleConnected(): Boolean = prefs.getBoolean(KEY_GOOGLE_CONNECTED, false)
    fun getGoogleEmail(): String? = prefs.getString(KEY_GOOGLE_EMAIL, null)
    fun getGoogleUpdated(): Long = prefs.getLong(KEY_GOOGLE_UPDATED, 0L)

    // ─── LLM provider API keys ─────────────────────────────────────────────────
    // Used by RemoteLlmConnector providers (OpenAI / Anthropic / Gemini).
    // Keys are read at provider-call time via () -> String? so they can
    // be rotated without restarting the registry.

    fun saveLlmKey(provider: String, key: String) =
        prefs.edit().putString(llmKeyPrefName(provider), key).apply()

    fun getLlmKey(provider: String): String? =
        prefs.getString(llmKeyPrefName(provider), null)

    fun clearLlmKey(provider: String) =
        prefs.edit().remove(llmKeyPrefName(provider)).apply()

    private fun llmKeyPrefName(provider: String): String =
        "llm_key_${provider.lowercase()}"

    // ─── Generic disconnect ────────────────────────────────────────────────────

    fun disconnect(id: String) {
        when (id) {
            "github" -> prefs.edit()
                .putBoolean(KEY_GITHUB_CONNECTED, false)
                .remove(KEY_GITHUB_TOKEN)
                .remove(KEY_GITHUB_USERNAME)
                .putLong(KEY_GITHUB_UPDATED, System.currentTimeMillis())
                .apply()

            "telegram" -> prefs.edit()
                .putBoolean(KEY_TELEGRAM_CONNECTED, false)
                .remove(KEY_TELEGRAM_TOKEN)
                .remove(KEY_TELEGRAM_USERNAME)
                .putLong(KEY_TELEGRAM_UPDATED, System.currentTimeMillis())
                .apply()

            "google" -> prefs.edit()
                .putBoolean(KEY_GOOGLE_CONNECTED, false)
                .remove(KEY_GOOGLE_EMAIL)
                .remove(KEY_GOOGLE_ID_TOKEN)
                .putLong(KEY_GOOGLE_UPDATED, System.currentTimeMillis())
                .apply()

            "openai", "anthropic", "gemini" -> clearLlmKey(id)
        }
    }

    companion object {
        private const val KEY_GITHUB_TOKEN     = "github_token"
        private const val KEY_GITHUB_CONNECTED = "github_connected"
        private const val KEY_GITHUB_USERNAME  = "github_username"
        private const val KEY_GITHUB_UPDATED   = "github_updated"

        private const val KEY_TELEGRAM_TOKEN     = "telegram_token"
        private const val KEY_TELEGRAM_CONNECTED = "telegram_connected"
        private const val KEY_TELEGRAM_USERNAME  = "telegram_username"
        private const val KEY_TELEGRAM_UPDATED   = "telegram_updated"

        private const val KEY_GOOGLE_CONNECTED = "google_connected"
        private const val KEY_GOOGLE_EMAIL     = "google_email"
        private const val KEY_GOOGLE_ID_TOKEN  = "google_id_token"
        private const val KEY_GOOGLE_UPDATED   = "google_updated"
    }
}
