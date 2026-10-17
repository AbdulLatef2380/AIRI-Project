package com.airi.assistant.integrations.google

import android.content.Context
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.airi.assistant.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential

/**
 * App-identity authentication through Android Credential Manager.
 *
 * This class deliberately handles authentication only. Google Workspace data
 * access remains in [GoogleAuthService] through AuthorizationClient and is
 * requested per connector surface after identity succeeds.
 */
class GoogleCredentialManagerClient(
    private val context: Context,
) {
    private val credentialManager: CredentialManager by lazy {
        CredentialManager.create(context)
    }

    suspend fun signIn(presentationContext: Context = context): Result<GoogleIdentityCredential> {
        val serverClientId = context.getString(R.string.default_web_client_id)
            .trim()
            .takeUnless { it.isBlank() || it.startsWith("REPLACE_WITH_") }
            ?: return Result.failure(IllegalStateException("Google web client ID is not configured"))

        val authorizedOnly = buildOption(serverClientId, filterByAuthorizedAccounts = true)
        // retrieve() deliberately returns Result, so a surrounding try/catch
        // cannot observe NoCredentialException. The old implementation therefore
        // never reached the account-picker fallback for first-time users.
        return retrieve(presentationContext, authorizedOnly).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error is NoCredentialException) {
                    // First-time users may have no authorized credential. The
                    // official flow retries with all Google accounts.
                    retrieve(
                        presentationContext,
                        buildOption(serverClientId, filterByAuthorizedAccounts = false),
                    )
                } else {
                    Result.failure(error)
                }
            }
        )
    }

    private suspend fun retrieve(presentationContext: Context, option: GetGoogleIdOption): Result<GoogleIdentityCredential> =
        runCatching {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            // Credential Manager may need an Activity-backed context to launch
            // account selection. The old Application context caused generic
            // provider failures on devices where no credential was cached.
            val result = credentialManager.getCredential(presentationContext, request)
            val credential = result.credential
            require(
                credential is CustomCredential &&
                    credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) { "Credential is not a Google ID token" }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val email = googleCredential.id.trim()
            require(email.isNotBlank()) { "Google credential did not provide an account identifier" }
            GoogleIdentityCredential(email = email, idToken = googleCredential.idToken)
        }

    private fun buildOption(
        serverClientId: String,
        filterByAuthorizedAccounts: Boolean,
    ) = GetGoogleIdOption.Builder()
        .setServerClientId(serverClientId)
        .setFilterByAuthorizedAccounts(filterByAuthorizedAccounts)
        .setAutoSelectEnabled(filterByAuthorizedAccounts)
        .build()
}

data class GoogleIdentityCredential(
    val email: String,
    val idToken: String,
)
