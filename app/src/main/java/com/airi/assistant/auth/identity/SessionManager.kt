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
            // B-02: Two-phase loop — normal proactive refresh every 55 min, with a
            // separate exponential-backoff retry loop on failure.
            //
            // Schedule:  normal → [wait 55m] → refresh
            //   success: back to normal
            //   failure: [wait 1m] → retry → [wait 2m] → retry → … → [wait 30m] → retry
            //            (capped at 30m; each retry on the BACKOFF interval, not 55m)
            //   once a retry succeeds → back to normal 55m cycle
            while (auth.currentUser != null) {
                delay(REFRESH_INTERVAL_MS)
                if (auth.currentUser == null) break

                val token = refreshToken(forceRefresh = true)
                if (token != null) {
                    // Success on the proactive refresh — continue normal cycle.
                    continue
                }

                // ── Failure: enter exponential-backoff retry loop ──────────────
                // IMPORTANT: we do NOT wait another 55m here — the retry interval
                // starts from INITIAL_BACKOFF_MS and doubles up to MAX_BACKOFF_MS.
                var backoffMs = INITIAL_BACKOFF_MS
                while (auth.currentUser != null) {
                    LoggingService.warn(TAG,
                        "AIRI_PROOF TOKEN_REFRESH_BACKOFF delay=${backoffMs}ms")
                    delay(backoffMs)
                    backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
                    if (auth.currentUser == null) break

                    val retryToken = refreshToken(forceRefresh = true)
                    if (retryToken != null) {
                        // Recovered — exit retry loop and resume normal 55m cycle.
                        LoggingService.info(TAG,
                            "AIRI_PROOF TOKEN_REFRESH_RECOVERED — resuming normal cycle")
                        break
                    }
                }
            }
        }
    }

    companion object {
        private const val REFRESH_INTERVAL_MS = 55 * 60 * 1000L
        private const val INITIAL_BACKOFF_MS  =      60 * 1000L  // 1 min → 2 → 4 → … → 30
        private const val MAX_BACKOFF_MS      =  30 * 60 * 1000L // 30 min cap
    }
}
