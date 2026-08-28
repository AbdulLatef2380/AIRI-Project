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
        val email = account.email ?: ""
        secureStorage.saveGoogleConnected(true, email)
        // ID tokens authenticate identity; they never authorize Google APIs.
        // Remove credentials retained by older application versions.
        secureStorage.clearGoogleIdToken()
    }

    fun disconnect() {
        dataAccessToken = null
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
    fun authorizeDataAccess(): Task<GoogleDataAuthorization> {
        val request = AuthorizationRequest.builder()
            .setRequestedScopes(GoogleDataScopes.connectionReadOnly)
            .build()
        return Identity.getAuthorizationClient(context)
            .authorize(request)
            .continueWith { task ->
                if (!task.isSuccessful) {
                    return@continueWith GoogleDataAuthorization.Unavailable
                }
                authorizationFromResult(task.result)
            }
    }

    /** Handles the result from the UI-owned authorization resolution. */
    fun completeDataAuthorization(resultIntent: Intent?): GoogleDataAuthorization =
        try {
            val result = Identity.getAuthorizationClient(context)
                .getAuthorizationResultFromIntent(resultIntent)
            authorizationFromResult(result)
        } catch (_: ApiException) {
            GoogleDataAuthorization.Cancelled
        }

    /** Only user-data OAuth access tokens may authenticate Google API calls. */
    fun getDataAccessToken(): String? = dataAccessToken

    /** Called when a Google resource server rejects the cached short-lived token. */
    fun clearDataAccessToken() {
        dataAccessToken = null
    }

    private fun authorizationFromResult(
        result: com.google.android.gms.auth.api.identity.AuthorizationResult
    ): GoogleDataAuthorization {
        if (result.hasResolution()) {
            val pendingIntent = result.pendingIntent ?: return GoogleDataAuthorization.Unavailable
            return GoogleDataAuthorization.ConsentRequired(pendingIntent)
        }
        val accessToken = result.accessToken
        if (accessToken.isNullOrBlank()) return GoogleDataAuthorization.Unavailable
        dataAccessToken = accessToken
        return GoogleDataAuthorization.Authorized
    }
}
