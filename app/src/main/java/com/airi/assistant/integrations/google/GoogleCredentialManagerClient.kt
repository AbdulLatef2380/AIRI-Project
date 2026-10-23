package com.airi.assistant.integrations.google

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.NoCredentialException
import com.airi.assistant.R
import com.airi.assistant.BuildConfig
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
        if (!GooglePresentationContextPolicy.hasActivity(presentationContext)) {
            return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.ACTIVITY_CONTEXT_REQUIRED)
            )
        }
        val serverClientId = runCatching { context.getString(R.string.default_web_client_id).trim() }
            .getOrNull()
            ?.takeUnless { it.isBlank() || it.startsWith("REPLACE_WITH_") }
            ?: return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.CLIENT_ID_MISSING)
            )
        if (!GoogleWebClientIdPolicy.isValid(serverClientId)) {
            return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.CLIENT_ID_MISSING)
            )
        }

        val registeredSha1s = BuildConfig.GOOGLE_ANDROID_OAUTH_CERT_SHA1S
        if (registeredSha1s.isBlank()) {
            return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.FIREBASE_CONFIGURATION)
            )
        }
        val installedSha1s = signingCertificateSha1s(presentationContext)
        if (installedSha1s.isEmpty()) {
            return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.FIREBASE_CONFIGURATION)
            )
        }
        if (installedSha1s.none { GoogleSigningCertificatePolicy.isRegistered(it, registeredSha1s) }) {
            return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.SIGNING_CERTIFICATE_NOT_REGISTERED)
            )
        }

        val authorizedOnly = runCatching {
            buildOption(serverClientId, filterByAuthorizedAccounts = true)
        }.getOrElse { error ->
            return Result.failure(
                GoogleSignInFailureException(GoogleSignInFailureReason.FIREBASE_CONFIGURATION, cause = error)
            )
        }
        // retrieve() deliberately returns Result, so a surrounding try/catch
        // cannot observe NoCredentialException. The old implementation therefore
        // never reached the account-picker fallback for first-time users.
        return retrieve(presentationContext, authorizedOnly).fold(
            onSuccess = { Result.success(it) },
            onFailure = { error ->
                if (error is NoCredentialException) {
                    // First-time users may have no authorized credential. The
                    // official flow retries with all Google accounts.
                    val anyAccountOption = runCatching {
                        buildOption(serverClientId, filterByAuthorizedAccounts = false)
                    }.getOrElse { optionError ->
                        return@fold Result.failure(
                            GoogleSignInFailureException(
                                GoogleSignInFailureReason.FIREBASE_CONFIGURATION,
                                cause = optionError,
                            )
                        )
                    }
                    retrieve(
                        presentationContext,
                        anyAccountOption,
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
            if (credential !is CustomCredential ||
                credential.type != GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
            ) {
                throw GoogleSignInFailureException(GoogleSignInFailureReason.INVALID_CREDENTIAL)
            }
            val googleCredential = GoogleIdTokenCredential.createFrom(credential.data)
            val email = googleCredential.id.trim()
            val idToken = googleCredential.idToken.trim()
            if (email.isBlank() || idToken.isBlank()) {
                throw GoogleSignInFailureException(GoogleSignInFailureReason.NO_ID_TOKEN)
            }
            GoogleIdentityCredential(email = email, idToken = idToken)
        }

    @Suppress("DEPRECATION")
    private fun signingCertificateSha1s(presentationContext: Context): Set<String> = runCatching {
        val packageManager = presentationContext.packageManager
        val packageInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageManager.getPackageInfo(
                presentationContext.packageName,
                PackageManager.GET_SIGNING_CERTIFICATES,
            )
        } else {
            packageManager.getPackageInfo(presentationContext.packageName, PackageManager.GET_SIGNATURES)
        }
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners.orEmpty()
        } else {
            packageInfo.signatures.orEmpty()
        }
        signatures.map { signature ->
            java.security.MessageDigest.getInstance("SHA-1")
                .digest(signature.toByteArray())
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
        }.toSet()
    }.getOrDefault(emptySet())

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

object GoogleWebClientIdPolicy {
    private val clientIdPattern = Regex("^[0-9]+-[A-Za-z0-9-]+\\.apps\\.googleusercontent\\.com$")

    fun isValid(clientId: String): Boolean = clientIdPattern.matches(clientId.trim())
}
