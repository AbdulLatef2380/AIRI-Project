package com.airi.assistant.connector

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConnectorAuthManager(context: Context) {
    private val TAG = "ConnectorAuthManager"

    private val prefs: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "connector_auth_vault", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) {
        Log.e(TAG, "Encrypted connector storage unavailable; connector auth is disabled (${e.javaClass.simpleName})")
        null
    }

    private val _authStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val authStates: StateFlow<Map<String, Boolean>> = _authStates.asStateFlow()

    init {
        prefs?.let { migrateLegacyFallback(context, it) }
        refreshAuthStates()
    }

    fun storeToken(
        connectorId: String,
        accessToken: String,
        refreshToken: String? = null,
        expiresAtMs: Long? = null
    ): Boolean {
        val storage = prefs ?: return false
        return try {
            storage.edit().putString(key(connectorId, "access_token"), accessToken)
                .apply { if (refreshToken != null) putString(key(connectorId, "refresh_token"), refreshToken) }
                .apply { if (expiresAtMs != null) putLong(key(connectorId, "expires_at"), expiresAtMs) }
                .apply()
            refreshAuthStates()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted connector token write failed (${e.javaClass.simpleName})")
            false
        }
    }

    fun getToken(connectorId: String): String? = prefs?.getString(key(connectorId, "access_token"), null)
    fun getRefreshToken(connectorId: String): String? = prefs?.getString(key(connectorId, "refresh_token"), null)
    fun isTokenValid(connectorId: String): Boolean {
        val token = getToken(connectorId) ?: return false
        val exp = prefs?.getLong(key(connectorId, "expires_at"), -1L) ?: return false
        return if (exp == -1L) token.isNotBlank() else System.currentTimeMillis() < exp
    }
    fun revokeToken(connectorId: String) {
        prefs?.edit()?.remove(key(connectorId, "access_token"))?.remove(key(connectorId, "refresh_token"))?.remove(key(connectorId, "expires_at"))?.apply()
        refreshAuthStates()
    }
    fun storeCredential(connectorId: String, credKey: String, value: String): Boolean {
        val storage = prefs ?: return false
        return try {
            storage.edit().putString(key(connectorId, "cred_$credKey"), value).apply()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Encrypted connector credential write failed (${e.javaClass.simpleName})")
            false
        }
    }
    fun getCredential(connectorId: String, credKey: String): String? =
        prefs?.getString(key(connectorId, "cred_$credKey"), null)
    fun clearCredential(connectorId: String, credKey: String) =
        prefs?.edit()?.remove(key(connectorId, "cred_$credKey"))?.apply()

    private fun key(id: String, field: String) = "auth_${id}_$field"
    private fun refreshAuthStates() {
        val keys = prefs?.all?.keys.orEmpty()
            .filter { it.endsWith("_access_token") }
            .map { it.removePrefix("auth_").removeSuffix("_access_token") }
        _authStates.value = keys.associateWith { isTokenValid(it) }
    }

    /**
     * Migrate credentials written by older builds' plaintext fallback only
     * after encrypted storage is available and the encrypted write succeeds.
     */
    private fun migrateLegacyFallback(context: Context, encrypted: SharedPreferences) {
        try {
            val legacy = context.getSharedPreferences("connector_auth_fallback", Context.MODE_PRIVATE)
            val values = legacy.all
            if (values.isEmpty()) return

            val supported = values.values.all { value ->
                value is String || value is Boolean || value is Int || value is Long ||
                    value is Float || (value is Set<*> && value.all { item -> item is String })
            }
            if (!supported) {
                Log.w(TAG, "Legacy connector credential migration deferred because a value type is unsupported")
                return
            }

            val editor = encrypted.edit()
            values.forEach { (key, value) ->
                if (encrypted.contains(key)) return@forEach
                when (value) {
                    is String -> editor.putString(key, value)
                    is Boolean -> editor.putBoolean(key, value)
                    is Int -> editor.putInt(key, value)
                    is Long -> editor.putLong(key, value)
                    is Float -> editor.putFloat(key, value)
                    is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toSet())
                }
            }

            if (editor.commit()) {
                if (!legacy.edit().clear().commit()) {
                    Log.w(TAG, "Encrypted credentials migrated, but legacy preference cleanup failed")
                }
            } else {
                Log.w(TAG, "Legacy connector credential migration deferred because encrypted write failed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Legacy connector credential migration deferred (${e.javaClass.simpleName})")
        }
    }
}
