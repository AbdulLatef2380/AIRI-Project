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
    private val tag = "ConnectorAuthManager"

    private val securePreferences: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "connector_auth_vault",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }.onFailure {
        Log.e(tag, "Encrypted connector credential storage is unavailable", it)
    }.getOrNull()

    private val _authStates = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val authStates: StateFlow<Map<String, Boolean>> = _authStates.asStateFlow()

    val isSecureStorageAvailable: Boolean
        get() = securePreferences != null

    init {
        refreshAuthStates()
    }

    fun storeToken(
        connectorId: String,
        accessToken: String,
        refreshToken: String? = null,
        expiresAtMs: Long? = null
    ): Boolean = updateSecurePreferences { editor ->
        editor.putString(key(connectorId, "access_token"), accessToken)
        if (refreshToken != null) editor.putString(key(connectorId, "refresh_token"), refreshToken)
        if (expiresAtMs != null) editor.putLong(key(connectorId, "expires_at"), expiresAtMs)
    }

    fun getToken(connectorId: String): String? =
        securePreferences?.getString(key(connectorId, "access_token"), null)

    fun getRefreshToken(connectorId: String): String? =
        securePreferences?.getString(key(connectorId, "refresh_token"), null)

    fun isTokenValid(connectorId: String): Boolean {
        val token = getToken(connectorId) ?: return false
        val expiresAtMillis = securePreferences?.getLong(key(connectorId, "expires_at"), -1L) ?: return false
        return if (expiresAtMillis == -1L) token.isNotBlank() else System.currentTimeMillis() < expiresAtMillis
    }

    fun revokeToken(connectorId: String): Boolean = updateSecurePreferences { editor ->
        editor.remove(key(connectorId, "access_token"))
        editor.remove(key(connectorId, "refresh_token"))
        editor.remove(key(connectorId, "expires_at"))
    }

    fun storeCredential(connectorId: String, credentialKey: String, value: String): Boolean =
        updateSecurePreferences { editor ->
            editor.putString(key(connectorId, "cred_$credentialKey"), value)
        }

    fun getCredential(connectorId: String, credentialKey: String): String? =
        securePreferences?.getString(key(connectorId, "cred_$credentialKey"), null)

    fun clearCredential(connectorId: String, credentialKey: String): Boolean =
        updateSecurePreferences { editor ->
            editor.remove(key(connectorId, "cred_$credentialKey"))
        }

    private fun updateSecurePreferences(update: (SharedPreferences.Editor) -> Unit): Boolean {
        val preferences = securePreferences ?: return false
        val editor = preferences.edit()
        update(editor)
        val committed = editor.commit()
        if (committed) refreshAuthStates()
        return committed
    }

    private fun key(connectorId: String, field: String): String = "auth_${connectorId}_$field"

    private fun refreshAuthStates() {
        val preferences = securePreferences ?: run {
            _authStates.value = emptyMap()
            return
        }
        val connectorIds = preferences.all.keys
            .filter { it.endsWith("_access_token") }
            .map { it.removePrefix("auth_").removeSuffix("_access_token") }
        _authStates.value = connectorIds.associateWith(::isTokenValid)
    }
}
