package com.airi.assistant.integrations.google

import android.content.Context
import android.content.Intent
import com.airi.assistant.auth.SecureStorage
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInAccount
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import com.google.android.gms.common.api.Scope

class GoogleAuthService(
    private val context: Context,
    private val secureStorage: SecureStorage
) {

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

    /**
     * AP-10: Returns the stored Google ID token for API bearer auth.
     * Prefer the live account token when available; fall back to the cached
     * SecureStorage token saved during [handleSignInSuccess].
     */
    fun getIdToken(): String? {
        val liveToken = GoogleSignIn.getLastSignedInAccount(context)?.idToken
        if (!liveToken.isNullOrBlank()) return liveToken
        return secureStorage.getGoogleIdToken()
    }
}
