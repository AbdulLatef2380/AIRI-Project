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

    suspend fun signIn(): Result<GoogleIdentityCredential> {
        val serverClientId = context.getString(R.string.default_web_client_id)
            .trim()
            .takeUnless { it.isBlank() || it.startsWith("REPLACE_WITH_") }
            ?: return Result.failure(IllegalStateException("Google web client ID is not configured"))

        val authorizedOnly = buildOption(serverClientId, filterByAuthorizedAccounts = true)
        return try {
            retrieve(authorizedOnly)
        } catch (_: NoCredentialException) {
            // First-time users may have no authorized credential. The official
            // flow retries with all Google accounts so the user can choose one.
            retrieve(buildOption(serverClientId, filterByAuthorizedAccounts = false))
        } catch (error: Exception) {
            Result.failure(error)
        }
    }

    private suspend fun retrieve(option: GetGoogleIdOption): Result<GoogleIdentityCredential> =
        runCatching {
            val request = GetCredentialRequest.Builder()
                .addCredentialOption(option)
                .build()
            val result = credentialManager.getCredential(context, request)
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
