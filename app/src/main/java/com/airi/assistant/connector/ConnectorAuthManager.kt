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

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(context, "connector_auth_vault", masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
    } catch (e: Exception) {
        Log.e(TAG, "EncryptedSharedPreferences unavailable — fallback: ${e.message}")
        context.getSharedPreferences("connector_auth_fallback", Context.MODE_PRIVATE)
    }

    private val _authStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val authStates: StateFlow<Map<String, Boolean>> = _authStates.asStateFlow()

    init { refreshAuthStates() }

    fun storeToken(connectorId: String, accessToken: String, refreshToken: String? = null, expiresAtMs: Long? = null) {
        prefs.edit().putString(key(connectorId, "access_token"), accessToken)
            .apply { if (refreshToken != null) putString(key(connectorId, "refresh_token"), refreshToken) }
            .apply { if (expiresAtMs  != null) putLong(key(connectorId, "expires_at"), expiresAtMs) }
            .apply()
        refreshAuthStates()
    }

    fun getToken(connectorId: String): String?  = prefs.getString(key(connectorId, "access_token"), null)
    fun getRefreshToken(connectorId: String): String? = prefs.getString(key(connectorId, "refresh_token"), null)
    fun isTokenValid(connectorId: String): Boolean {
        val token = getToken(connectorId) ?: return false
        val exp = prefs.getLong(key(connectorId, "expires_at"), -1L)
        return if (exp == -1L) token.isNotBlank() else System.currentTimeMillis() < exp
    }
    fun revokeToken(connectorId: String) {
        prefs.edit().remove(key(connectorId, "access_token")).remove(key(connectorId, "refresh_token")).remove(key(connectorId, "expires_at")).apply()
        refreshAuthStates()
    }
    fun storeCredential(connectorId: String, credKey: String, value: String) = prefs.edit().putString(key(connectorId, "cred_$credKey"), value).apply()
    fun getCredential(connectorId: String, credKey: String): String? = prefs.getString(key(connectorId, "cred_$credKey"), null)
    fun clearCredential(connectorId: String, credKey: String) = prefs.edit().remove(key(connectorId, "cred_$credKey")).apply()

    private fun key(id: String, field: String) = "auth_${id}_$field"
    private fun refreshAuthStates() {
        val keys = prefs.all.keys.filter { it.endsWith("_access_token") }.map { it.removePrefix("auth_").removeSuffix("_access_token") }
        _authStates.value = keys.associateWith { isTokenValid(it) }
    }
}
