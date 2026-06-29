package com.airi.assistant.auth

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted storage for OAuth tokens, API keys and device-binding secrets.
 *
 * ── Phase-3 P0 hardening ──────────────────────────────────────────────────────
 * Previous behavior: if EncryptedSharedPreferences failed to initialise (corrupt
 * keystore, locked device, etc.) the class silently dropped to plaintext
 * SharedPreferences — meaning tokens and API keys were written to a
 * world-readable-by-app file in plain text without any visible signal.
 *
 * New behavior:
 *   • If encryption fails, we fall back to an *in-memory only* prefs
 *     implementation. Reads return null and writes are dropped at process end.
 *   • [isEncrypted] reports the live state. UI surfaces (Settings, Connectors)
 *     can read it to warn the user that integrations cannot be persisted on
 *     this device until the keystore is restored.
 *   • Plaintext disk writes are never attempted.
 *
 * ── Public surface ────────────────────────────────────────────────────────────
 * All existing call sites (saveGithubToken, getLlmKey, disconnect, …) continue
 * to compile and run unchanged. The only addition is the [isEncrypted] property.
 */
class SecureStorage(context: Context) {

    /**
     * True when the underlying store is EncryptedSharedPreferences.
     * False when the keystore-backed implementation failed to initialise and
     * we are running on the in-memory fallback.
     */
    val isEncrypted: Boolean

    private val prefs: SharedPreferences

    init {
        var encrypted = false
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val real = EncryptedSharedPreferences.create(
                context,
                "airi_secure_integrations",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encrypted = true
            real
        } catch (e: Exception) {
            Log.e(
                "SecureStorage",
                "EncryptedSharedPreferences init failed; falling back to IN-MEMORY only " +
                    "(no plaintext disk writes). Cause: ${e.message}"
            )
            InMemorySharedPreferences()
        }
        isEncrypted = encrypted
    }

    /**
     * Safe writer for EncryptedSharedPreferences:
     *   • Drops writes when the key is blank (the underlying Tink layer
     *     refuses empty keys with an opaque exception).
     *   • Removes the entry instead of storing an empty / blank value
     *     (some EncryptedSharedPreferences implementations crash on "" too).
     *   • Catches and swallows any backing-store exception so a single bad
     *     entry can never crash the Settings screen.
     */
    private fun SharedPreferences.Editor.safePutString(key: String, value: String?): SharedPreferences.Editor {
        if (key.isBlank()) {
            Log.w("SecureStorage", "safePutString: ignored blank key")
            return this
        }
        return try {
            if (value.isNullOrBlank()) remove(key) else putString(key, value)
        } catch (t: Throwable) {
            Log.w("SecureStorage", "safePutString($key) failed: ${t.message}")
            this
        }
    }

    // ─── GitHub ────────────────────────────────────────────────────────────────

    fun saveGithubToken(token: String) =
        prefs.edit().safePutString(KEY_GITHUB_TOKEN, token).apply()

    fun getGithubToken(): String? = prefs.getString(KEY_GITHUB_TOKEN, null)

    fun saveGithubConnected(connected: Boolean, username: String = "") {
        prefs.edit()
            .putBoolean(KEY_GITHUB_CONNECTED, connected)
            .safePutString(KEY_GITHUB_USERNAME, username)
            .putLong(KEY_GITHUB_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun isGithubConnected(): Boolean = prefs.getBoolean(KEY_GITHUB_CONNECTED, false)
    fun getGithubUsername(): String = prefs.getString(KEY_GITHUB_USERNAME, "") ?: ""
    fun getGithubUpdated(): Long = prefs.getLong(KEY_GITHUB_UPDATED, 0L)

    // ─── Telegram ──────────────────────────────────────────────────────────────

    fun saveTelegramToken(token: String) =
        prefs.edit().safePutString(KEY_TELEGRAM_TOKEN, token).apply()

    fun getTelegramToken(): String? = prefs.getString(KEY_TELEGRAM_TOKEN, null)

    fun saveTelegramConnected(connected: Boolean, username: String = "") {
        prefs.edit()
            .putBoolean(KEY_TELEGRAM_CONNECTED, connected)
            .safePutString(KEY_TELEGRAM_USERNAME, username)
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
            .safePutString(KEY_GOOGLE_EMAIL, email)
            .putLong(KEY_GOOGLE_UPDATED, System.currentTimeMillis())
            .apply()
    }

    fun saveGoogleIdToken(token: String) =
        prefs.edit().safePutString(KEY_GOOGLE_ID_TOKEN, token).apply()

    fun getGoogleIdToken(): String? = prefs.getString(KEY_GOOGLE_ID_TOKEN, null)
    fun isGoogleConnected(): Boolean = prefs.getBoolean(KEY_GOOGLE_CONNECTED, false)
    fun getGoogleEmail(): String? = prefs.getString(KEY_GOOGLE_EMAIL, null)
    fun getGoogleUpdated(): Long = prefs.getLong(KEY_GOOGLE_UPDATED, 0L)

    // ─── LLM provider API keys ─────────────────────────────────────────────────

    fun saveLlmKey(provider: String, key: String) =
        prefs.edit().safePutString(llmKeyPrefName(provider), key).apply()

    fun getLlmKey(provider: String): String? =
        prefs.getString(llmKeyPrefName(provider), null)

    fun clearLlmKey(provider: String) =
        prefs.edit().remove(llmKeyPrefName(provider)).apply()

    private fun llmKeyPrefName(provider: String): String =
        "llm_key_${provider.lowercase()}"

    // ─── Device binding ────────────────────────────────────────────────────────

    fun saveDeviceFingerprint(fp: String) =
        prefs.edit().safePutString(KEY_DEVICE_FP, fp).apply()

    fun getDeviceFingerprint(): String? = prefs.getString(KEY_DEVICE_FP, null)

    fun clearDeviceFingerprint() =
        prefs.edit().remove(KEY_DEVICE_FP).apply()

    fun saveInstallUuid(uuid: String) =
        prefs.edit().safePutString(KEY_INSTALL_UUID, uuid).apply()

    fun getInstallUuid(): String? = prefs.getString(KEY_INSTALL_UUID, null)

    // ─── Generic integration token store (Task 8: Notion + future integrations) ─

    /**
     * Store a Personal Access Token (PAT) for the given integration ID.
     * Stored under the key "integration_token_{id}" in AES256-GCM encrypted prefs.
     *
     * @param integrationId  e.g. "notion", "linear", "zapier"
     * @param token          The PAT / integration secret. Blank value removes the entry.
     */
    fun saveIntegrationToken(integrationId: String, token: String) =
        prefs.edit().safePutString(integrationTokenKey(integrationId), token).apply()

    /**
     * Retrieve the stored token for the given integration, or null if not set.
     */
    fun getIntegrationToken(integrationId: String): String? =
        prefs.getString(integrationTokenKey(integrationId), null)

    /**
     * Clear the stored token for the given integration (disconnect).
     */
    fun clearIntegrationToken(integrationId: String) =
        prefs.edit().remove(integrationTokenKey(integrationId)).apply()

    private fun integrationTokenKey(id: String): String = "integration_token_${id.lowercase()}"

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
            else -> clearIntegrationToken(id)   // covers "notion" and future integrations
        }
    }

    companion object {
        private const val KEY_DEVICE_FP        = "device_fingerprint"
        private const val KEY_INSTALL_UUID     = "install_uuid"

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

/**
 * Process-lifetime, in-memory SharedPreferences fallback used when the
 * EncryptedSharedPreferences master key cannot be initialised.
 *
 * Discards everything when the process dies. This is intentional: a user with
 * a broken keystore should re-connect their integrations rather than have
 * tokens silently persisted in plaintext.
 *
 * Thread safety: backed by a synchronized map; commit/apply are no-ops past
 * the in-memory mutation.
 */
private class InMemorySharedPreferences : SharedPreferences {
    private val map: MutableMap<String, Any?> = java.util.concurrent.ConcurrentHashMap()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)
    override fun getString(key: String?, defValue: String?): String? =
        (map[key] as? String) ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (map[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (map[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (map[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (map[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = map.containsKey(key)
    override fun edit(): SharedPreferences.Editor = MemEditor()
    override fun registerOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.add(l)
    }
    override fun unregisterOnSharedPreferenceChangeListener(l: SharedPreferences.OnSharedPreferenceChangeListener) {
        listeners.remove(l)
    }

    private inner class MemEditor : SharedPreferences.Editor {
        private val pending = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals.add(key); pending.remove(key) }
        override fun clear(): SharedPreferences.Editor = apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) map.clear()
            for (k in removals) map.remove(k)
            for ((k, v) in pending) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
