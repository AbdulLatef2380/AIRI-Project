package com.airi.assistant.integrations.google

import android.content.Context
import android.content.Intent
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.connector.ConnectorState
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Google identity is shared, but user-data grants and short-lived access tokens are isolated per product surface.
 * Only granted scope names are persisted in SecureStorage; access tokens remain memory-only and are reacquired
 * through Google Identity Services, which owns refresh-token handling and token expiry.
 */
class GoogleAuthService(
    private val context: Context,
    private val secureStorage: SecureStorage,
) {
    private data class SurfaceSession(val accessToken: String, val scopes: Set<String>)

    private val sessions = ConcurrentHashMap<String, SurfaceSession>()
    private val pendingScopes = ConcurrentHashMap<String, Set<String>>()
    private val surfaceStates = ConcurrentHashMap<String, kotlinx.coroutines.flow.MutableStateFlow<ConnectorState>>()

    private val gso: GoogleSignInOptions by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
    }

    fun getSignInIntent(): Intent = GoogleSignIn.getClient(context, gso).signInIntent

    fun handleSignInSuccess(account: GoogleSignInAccount) {
        val email = account.email.orEmpty()
        if (email.isBlank()) return
        val previousEmail = secureStorage.getGoogleEmail()
        if (!previousEmail.isNullOrBlank() && !previousEmail.equals(email, ignoreCase = true)) {
            secureStorage.clearGoogleSurfaceGrants()
            sessions.clear()
            pendingScopes.clear()
            GoogleDataScopes.surfaceIds.forEach { setSurfaceState(it, disconnectedState()) }
        }
        secureStorage.saveGoogleConnected(true, email)
        // ID tokens authenticate identity; they never authorize Google APIs. Clear tokens retained by old versions.
        secureStorage.clearGoogleIdToken()
    }

    /** Global identity disconnect. Surface-specific disconnects must use [disconnectSurface]. */
    fun disconnect(): Boolean {
        sessions.clear()
        pendingScopes.clear()
        GoogleDataScopes.surfaceIds.forEach { setSurfaceState(it, disconnectedState()) }
        runCatching { GoogleSignIn.getClient(context, gso).signOut() }
        return secureStorage.disconnect("google")
    }

    /** Disconnect one Google product without signing the shared Google identity out. */
    fun disconnectSurface(surfaceId: String): Boolean {
        if (surfaceId !in GoogleDataScopes.surfaceIds) return false
        sessions.remove(surfaceId)
        pendingScopes.remove(surfaceId)
        val cleared = secureStorage.clearGoogleSurfaceGrant(surfaceId)
        setSurfaceState(surfaceId, disconnectedState())
        return cleared
    }

    fun getLastSignedInEmail(): String? =
        GoogleSignIn.getLastSignedInAccount(context)?.email ?: secureStorage.getGoogleEmail()

    fun stateForSurface(surfaceId: String): kotlinx.coroutines.flow.StateFlow<ConnectorState> =
        mutableStateForSurface(surfaceId)

    fun getDataAccessToken(surfaceId: String): String? = sessions[surfaceId]?.accessToken

    fun hasAuthorizedDataScopes(surfaceId: String, requiredScopes: Set<String>): Boolean {
        val session = sessions[surfaceId] ?: return false
        return requiredScopes.isNotEmpty() && requiredScopes.all { it in session.scopes }
    }

    /** Requests scopes for exactly one catalog surface; cross-surface scopes are rejected. */
    fun authorizeDataAccess(surfaceId: String, requestedScopes: Set<String>): Task<GoogleDataAuthorization> {
        if (!secureStorage.isEncrypted) return Tasks.forResult(GoogleDataAuthorization.Unavailable)
        val scopes = requestedScopes.filter(String::isNotBlank).toSet()
        if (!GoogleDataScopes.isValidSurfaceRequest(surfaceId, scopes)) {
            return Tasks.forException(IllegalArgumentException("Google authorization scope does not belong to this connector surface"))
        }
        pendingScopes[surfaceId] = scopes
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleDataScopes.fromActionScopes(scopes))
            .build()
        return Identity.getAuthorizationClient(context).authorize(request).continueWith { task ->
            if (!task.isSuccessful) {
                pendingScopes.remove(surfaceId)
                markNeedsAuthorization(surfaceId, "Google authorization could not be refreshed")
                return@continueWith GoogleDataAuthorization.Unavailable
            }
            authorizationFromResult(surfaceId, task.result, scopes)
        }
    }

    /** Rehydrates previously granted scopes after process restart without launching a consent UI. */
    suspend fun restorePreviouslyGrantedAccess() = withContext(Dispatchers.IO) {
        if (!secureStorage.isEncrypted) {
            GoogleDataScopes.surfaceIds.forEach { setSurfaceState(it, disconnectedState()) }
            return@withContext
        }
        if (getLastSignedInEmail().isNullOrBlank()) {
            secureStorage.getGoogleSurfaceGrants().keys.forEach {
                markNeedsAuthorization(it, "Sign in to the Google account used for this connector")
            }
            return@withContext
        }
        secureStorage.getGoogleSurfaceGrants().forEach { (surfaceId, scopes) ->
            if (!GoogleDataScopes.isValidSurfaceRequest(surfaceId, scopes)) {
                secureStorage.clearGoogleSurfaceGrant(surfaceId)
                setSurfaceState(surfaceId, disconnectedState())
                return@forEach
            }
            try {
                val request = AuthorizationRequest.builder()
                    .setRequestedScopes(GoogleDataScopes.fromActionScopes(scopes))
                    .build()
                val result = Tasks.await(Identity.getAuthorizationClient(context).authorize(request))
                when (authorizationFromResult(surfaceId, result, scopes)) {
                    GoogleDataAuthorization.Authorized -> Unit
                    is GoogleDataAuthorization.ConsentRequired -> Unit // State is marked; UI may launch it on explicit request.
                    GoogleDataAuthorization.Cancelled,
                    GoogleDataAuthorization.Unavailable -> markNeedsAuthorization(surfaceId, "Google authorization needs attention")
                }
            } catch (_: Exception) {
                markNeedsAuthorization(surfaceId, "Google authorization could not be restored")
            }
        }
    }

    /** Completes the UI-owned consent resolution for the same surface and unchanged scope set. */
    fun completeDataAuthorization(
        resultIntent: Intent?,
        surfaceId: String,
        requestedScopes: Set<String>,
    ): GoogleDataAuthorization {
        if (!secureStorage.isEncrypted) {
            pendingScopes.remove(surfaceId)
            return GoogleDataAuthorization.Unavailable
        }
        val pending = pendingScopes[surfaceId]
        if (pending.isNullOrEmpty() || requestedScopes != pending ||
            !GoogleDataScopes.isValidSurfaceRequest(surfaceId, requestedScopes)
        ) {
            pendingScopes.remove(surfaceId)
            return GoogleDataAuthorization.Unavailable
        }
        if (resultIntent == null) {
            pendingScopes.remove(surfaceId)
            markNeedsAuthorization(surfaceId, "Google consent was cancelled")
            return GoogleDataAuthorization.Cancelled
        }
        return try {
            val result = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(resultIntent)
            authorizationFromResult(surfaceId, result, requestedScopes)
        } catch (_: ApiException) {
            pendingScopes.remove(surfaceId)
            markNeedsAuthorization(surfaceId, "Google consent was cancelled")
            GoogleDataAuthorization.Cancelled
        } catch (_: Exception) {
            pendingScopes.remove(surfaceId)
            markNeedsAuthorization(surfaceId, "Google authorization result could not be verified")
            GoogleDataAuthorization.Unavailable
        }
    }

    /** Clears one expired in-memory token while retaining the encrypted grant record for reauthorization. */
    fun clearDataAccessToken(surfaceId: String) {
        sessions.remove(surfaceId)
        pendingScopes.remove(surfaceId)
        markNeedsAuthorization(surfaceId, "Google authorization expired")
    }

    private fun authorizationFromResult(
        surfaceId: String,
        result: com.google.android.gms.auth.api.identity.AuthorizationResult,
        requestedScopes: Set<String>,
    ): GoogleDataAuthorization {
        if (!GoogleDataScopes.isValidSurfaceRequest(surfaceId, requestedScopes)) {
            pendingScopes.remove(surfaceId)
            markNeedsAuthorization(surfaceId, "Google authorization scope was rejected")
            return GoogleDataAuthorization.Unavailable
        }
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent ?: run {
                pendingScopes.remove(surfaceId)
                markNeedsAuthorization(surfaceId, "Google consent is unavailable")
                return GoogleDataAuthorization.Unavailable
            }
            markNeedsAuthorization(surfaceId, "Google consent required")
            return GoogleDataAuthorization.ConsentRequired(pendingIntent)
        }
        val token = result.accessToken
        if (token.isNullOrBlank()) {
            pendingScopes.remove(surfaceId)
            markNeedsAuthorization(surfaceId, "Google did not issue an access token")
            return GoogleDataAuthorization.Unavailable
        }
        if (!secureStorage.saveGoogleSurfaceScopes(surfaceId, requestedScopes)) {
            pendingScopes.remove(surfaceId)
            markNeedsAuthorization(surfaceId, "Encrypted storage is unavailable; Google access was not retained")
            return GoogleDataAuthorization.Unavailable
        }
        sessions[surfaceId] = SurfaceSession(token, requestedScopes.toSet())
        pendingScopes.remove(surfaceId)
        setSurfaceState(
            surfaceId,
            ConnectorState(
                connected = true,
                healthy = true,
                statusLine = "Google access authorized",
                lastUpdatedMs = System.currentTimeMillis(),
            ),
        )
        return GoogleDataAuthorization.Authorized
    }

    private fun markNeedsAuthorization(surfaceId: String, message: String) {
        if (surfaceId !in GoogleDataScopes.surfaceIds) return
        val hasSavedGrant = secureStorage.getGoogleSurfaceScopes(surfaceId).isNotEmpty()
        setSurfaceState(
            surfaceId,
            ConnectorState(
                connected = hasSavedGrant,
                healthy = false,
                statusLine = message,
                errorMessage = message,
            ),
        )
    }

    private fun mutableStateForSurface(surfaceId: String) = surfaceStates.getOrPut(surfaceId) {
        val hasSavedGrant = secureStorage.getGoogleSurfaceScopes(surfaceId).isNotEmpty()
        kotlinx.coroutines.flow.MutableStateFlow(
            if (hasSavedGrant) {
                ConnectorState(
                    connected = true,
                    healthy = false,
                    statusLine = "Restoring Google authorization",
                    errorMessage = "Google access token is being renewed",
                )
            } else disconnectedState()
        )
    }

    private fun setSurfaceState(surfaceId: String, state: ConnectorState) {
        mutableStateForSurface(surfaceId).value = state
    }

    private fun disconnectedState() = ConnectorState(
        connected = false,
        healthy = false,
        statusLine = "Not connected",
    )
}
