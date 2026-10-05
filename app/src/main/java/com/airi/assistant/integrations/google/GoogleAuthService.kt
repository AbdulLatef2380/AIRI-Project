package com.airi.assistant.integrations.google

import android.content.Context
import android.content.Intent
import android.util.Log
import com.airi.assistant.auth.SecureStorage
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

class GoogleAuthService(
    private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val TAG = "GoogleAuthService"
        private const val API_SCOPE = "oauth2:" +
            "https://www.googleapis.com/auth/gmail.readonly " +
            "https://www.googleapis.com/auth/drive.readonly " +
            "https://www.googleapis.com/auth/calendar.readonly"
    }

    private val gso: GoogleSignInOptions by lazy {
        val builder = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestScopes(
                Scope("https://www.googleapis.com/auth/gmail.readonly"),
                Scope("https://www.googleapis.com/auth/drive.readonly"),
                Scope("https://www.googleapis.com/auth/calendar.readonly")
            )

        try {
            val webClientId = context.getString(
                context.resources.getIdentifier(
                    "default_web_client_id", "string", context.packageName
                )
            )
            if (webClientId.isNotBlank()) {
                builder.requestIdToken(webClientId)
            }
        } catch (e: Exception) {
        }

        builder.build()
    }

    fun getSignInIntent(): Intent = GoogleSignIn.getClient(context, gso).signInIntent

    fun handleSignInSuccess(account: GoogleSignInAccount) {
        val email = account.email ?: ""
        secureStorage.saveGoogleConnected(true, email)
        account.idToken?.takeIf { it.isNotBlank() }?.let { token ->
            secureStorage.saveGoogleIdToken(token)
        }
    }

    fun disconnect() {
        try {
            GoogleSignIn.getClient(context, gso).signOut()
        } catch (e: Exception) {
        }
        secureStorage.disconnect("google")
    }

    fun getLastSignedInEmail(): String? = GoogleSignIn.getLastSignedInAccount(context)?.email

    /** Identity token only; it must not be used as Google REST API bearer auth. */
    @Deprecated("Use getAccessToken() for Gmail, Drive, or Calendar API requests.")
    fun getIdToken(): String? {
        val liveToken = GoogleSignIn.getLastSignedInAccount(context)?.idToken
        if (!liveToken.isNullOrBlank()) return liveToken
        return secureStorage.getGoogleIdToken()
    }

    /**
     * Return an OAuth access token for Google REST APIs. An ID token proves
     * identity but is not a bearer credential for Gmail, Drive, or Calendar.
     * Call from an IO dispatcher; GoogleAuthUtil performs synchronous I/O.
     */
    fun getAccessToken(): String? {
        val account = GoogleSignIn.getLastSignedInAccount(context)?.account ?: return null
        return try {
            GoogleAuthUtil.getToken(context, account, API_SCOPE)
        } catch (e: Exception) {
            Log.w(TAG, "Google API access token unavailable (${e.javaClass.simpleName})")
            null
        }
    }
}
