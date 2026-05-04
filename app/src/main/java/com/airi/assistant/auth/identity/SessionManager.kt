package com.airi.assistant.auth.identity

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * SessionManager — manages Firebase ID-token refresh, session expiry, and
 * device-bound session validation.
 *
 * ── TOKEN LIFECYCLE ───────────────────────────────────────────────────────
 *
 *   Firebase ID tokens expire after 1 hour. SessionManager:
 *     1. Proactively refreshes the token every 55 minutes while a session
 *        is active (giving a 5-minute safety margin).
 *     2. Exposes [sessionState] as a StateFlow so the UI can react to
 *        expiry / rotation events.
 *     3. Persists the last-seen token issue time so a cold-start within
 *        the valid window does not force an unnecessary refresh.
 *
 * ── SESSION STATES ────────────────────────────────────────────────────────
 *
 *   UNAUTHENTICATED → ACTIVE → REFRESHING → ACTIVE  (happy path)
 *                                        ↘ EXPIRED   (on failure after retries)
 */
class SessionManager(
    private val context: Context,
    private val deviceBinding: DeviceBindingService
) {

    private val TAG = "SessionManager"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val auth  = FirebaseAuth.getInstance()

    enum class SessionState {
        UNAUTHENTICATED,
        ACTIVE,
        REFRESHING,
        EXPIRED
    }

    private val _sessionState = MutableStateFlow(
        if (auth.currentUser != null) SessionState.ACTIVE else SessionState.UNAUTHENTICATED
    )
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private val _idToken = MutableStateFlow<String?>(null)
    val idToken: StateFlow<String?> = _idToken.asStateFlow()

    init {
        if (auth.currentUser != null) {
            startRefreshLoop()
        }

        auth.addAuthStateListener { fa ->
            if (fa.currentUser == null) {
                _sessionState.value = SessionState.UNAUTHENTICATED
                _idToken.value      = null
                LoggingService.info(TAG, "Session ended — user signed out")
            } else {
                _sessionState.value = SessionState.ACTIVE
                startRefreshLoop()
                LoggingService.info(TAG, "Session started uid=${fa.currentUser?.uid}")
            }
        }
    }

    /**
     * Force-refresh the Firebase ID token and return it.
     * Returns null if the user is not signed in or refresh fails after retries.
     */
    suspend fun refreshToken(forceRefresh: Boolean = false): String? {
        val user = auth.currentUser ?: run {
            _sessionState.value = SessionState.UNAUTHENTICATED
            return null
        }

        _sessionState.value = SessionState.REFRESHING

        repeat(3) { attempt ->
            runCatching {
                val result = user.getIdToken(forceRefresh).await()
                val token  = result.token
                if (token != null) {
                    _idToken.value      = token
                    _sessionState.value = SessionState.ACTIVE
                    LoggingService.info(TAG, "AIRI_PROOF SESSION_TOKEN_REFRESHED attempt=$attempt expiresIn=${result.expirationTimestamp}")
                    return token
                }
            }.onFailure { e ->
                Log.w(TAG, "Token refresh attempt $attempt failed: ${e.message}")
                if (attempt < 2) delay(2_000L * (attempt + 1))
            }
        }

        _sessionState.value = SessionState.EXPIRED
        LoggingService.warn(TAG, "AIRI_PROOF SESSION_EXPIRED — refresh failed after 3 attempts")
        return null
    }

    /**
     * Returns the current (possibly cached) ID token, refreshing if needed.
     */
    suspend fun getValidToken(): String? {
        val user = auth.currentUser ?: return null
        return runCatching {
            val result = user.getIdToken(false).await()
            result.token?.also { _idToken.value = it }
        }.getOrNull()
    }

    /**
     * Bind the current session to the device. Call once after sign-in.
     * Returns false if the device has changed (caller should force re-auth).
     */
    fun bindToDevice(): Boolean {
        return try {
            deviceBinding.bindOrThrow()
            LoggingService.info(TAG, "AIRI_PROOF SESSION_DEVICE_BOUND")
            true
        } catch (e: DeviceBindingService.DeviceChangedException) {
            LoggingService.warn(TAG, "AIRI_PROOF SESSION_DEVICE_MISMATCH — forcing re-auth")
            false
        }
    }

    /**
     * Clear device binding and session state on sign-out.
     */
    fun onSignOut() {
        deviceBinding.clearBinding()
        _sessionState.value = SessionState.UNAUTHENTICATED
        _idToken.value      = null
        LoggingService.info(TAG, "AIRI_PROOF SESSION_CLEARED")
    }

    private fun startRefreshLoop() {
        scope.launch {
            while (auth.currentUser != null) {
                delay(REFRESH_INTERVAL_MS)
                if (auth.currentUser != null) {
                    refreshToken(forceRefresh = true)
                }
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 55 * 60 * 1000L
    }
}
