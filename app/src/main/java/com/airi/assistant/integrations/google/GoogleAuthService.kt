package com.airi.assistant.integrations.google

import android.content.Context
import android.content.Intent
import com.airi.assistant.auth.SecureStorage
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.tasks.Task
import com.google.android.gms.tasks.Tasks

class GoogleAuthService(
    private val context: Context,
    private val secureStorage: SecureStorage
) {

    /**
     * In-memory user-data access token. It is intentionally never persisted:
     * Google Identity Services can renew a previously granted authorization in
     * a later foreground session, while a stolen on-device token expires.
     */
    @Volatile
    private var dataAccessToken: String? = null

    @Volatile
    private var dataAccessScopes: Set<String> = emptySet()

    @Volatile
    private var pendingDataScopes: Set<String> = emptySet()

    private val gso: GoogleSignInOptions by lazy {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .build()
    }

    fun getSignInIntent(): Intent = GoogleSignIn.getClient(context, gso).signInIntent

    fun handleSignInSuccess(account: GoogleSignInAccount) {
        // Identity may have changed within the same process; its data grant must
        // never inherit the previous account's memory-only access token.
        dataAccessToken = null
        dataAccessScopes = emptySet()
        pendingDataScopes = emptySet()
        val email = account.email ?: ""
        secureStorage.saveGoogleConnected(true, email)
        // ID tokens authenticate identity; they never authorize Google APIs.
        // Remove credentials retained by older application versions.
        secureStorage.clearGoogleIdToken()
    }

    fun disconnect() {
        dataAccessToken = null
        dataAccessScopes = emptySet()
        pendingDataScopes = emptySet()
        try {
            GoogleSignIn.getClient(context, gso).signOut()
        } catch (_: Exception) {
            // Best effort only; encrypted credentials are still cleared below.
        }
        secureStorage.disconnect("google")
    }

    fun getLastSignedInEmail(): String? = GoogleSignIn.getLastSignedInAccount(context)?.email

    /**
     * Requests the Google API scopes only after a user deliberately connects
     * the Google data integration. A successful no-resolution result means
     * consent already exists; a pending intent must be launched by the UI.
     */
    fun isDataAccessAuthorizedFor(scopes: Set<String>): Boolean =
        scopes.isNotEmpty() && !dataAccessToken.isNullOrBlank() && dataAccessScopes == scopes

    fun authorizeDataAccess(requestedScopes: Set<String>): Task<GoogleDataAuthorization> {
        val scopes = requestedScopes.filter(String::isNotBlank).toSet()
        if (scopes.isEmpty() || scopes.any { it !in GoogleDataScopes.knownReadOnlyScopes }) {
            return Tasks.forException(IllegalArgumentException("Google data access requested an undeclared scope"))
        }
        pendingDataScopes = scopes
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleDataScopes.fromActionScopes(scopes))
            .build()
        return Identity.getAuthorizationClient(context)
            .authorize(request)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    pendingDataScopes = emptySet()
                    return@continueWith GoogleDataAuthorization.Unavailable
                }
                authorizationFromResult(task.result, scopes)
            }
    }

    /** Handles the result from the UI-owned authorization resolution. */
    fun completeDataAuthorization(
        resultIntent: Intent?,
        requestedScopes: Set<String> = pendingDataScopes,
    ): GoogleDataAuthorization {
        if (pendingDataScopes.isEmpty() || requestedScopes != pendingDataScopes) {
            pendingDataScopes = emptySet()
            return GoogleDataAuthorization.Unavailable
        }
        return try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(resultIntent)
            authorizationFromResult(result, requestedScopes)
        } catch (_: ApiException) {
            pendingDataScopes = emptySet()
            GoogleDataAuthorization.Cancelled
        }
    }

    /** Only user-data OAuth access tokens may authenticate Google API calls. */
    fun getDataAccessToken(): String? = dataAccessToken

    /** Called when a Google resource server rejects the cached short-lived token. */
    fun clearDataAccessToken() {
        dataAccessToken = null
        dataAccessScopes = emptySet()
        pendingDataScopes = emptySet()
    }

    private fun authorizationFromResult(
        result: com.google.android.gms.auth.api.identity.AuthorizationResult,
        requestedScopes: Set<String>,
    ): GoogleDataAuthorization {
        if (requestedScopes.isEmpty() || requestedScopes.any { it !in GoogleDataScopes.knownReadOnlyScopes }) {
            pendingDataScopes = emptySet()
            return GoogleDataAuthorization.Unavailable
        }
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent ?: run {
                pendingDataScopes = emptySet()
                return GoogleDataAuthorization.Unavailable
            }
            return GoogleDataAuthorization.ConsentRequired(pendingIntent)
        }
        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) {
            pendingDataScopes = emptySet()
            return GoogleDataAuthorization.Unavailable
        }
        dataAccessToken = accessToken
        dataAccessScopes = requestedScopes.toSet()
        pendingDataScopes = emptySet()
        return GoogleDataAuthorization.Authorized
    }
}
