package com.airi.assistant.ui.screens

import com.airi.assistant.integrations.google.GoogleSignInFailureClassifier
import com.airi.assistant.integrations.google.GoogleSignInFailureReason

/**
 * User-safe outcomes for the authentication entry screen.
 *
 * Authentication providers keep their detailed failures in the audit/logging layer.
 * The UI intentionally maps them to small actionable states so it never exposes a
 * token, provider exception detail, or an ambiguous silent failure to the user.
 */
internal enum class LoginFeedback {
    GOOGLE_NOT_CONFIGURED,
    GOOGLE_CLIENT_ID_MISSING,
    GOOGLE_FIREBASE_CONFIG_MISSING,
    GOOGLE_PROVIDER_DISABLED,
    GOOGLE_SHA_MISMATCH,
    GOOGLE_APP_MISCONFIGURED,
    GOOGLE_CREDENTIAL_MANAGER_FAILURE,
    GOOGLE_NETWORK_FAILURE,
    GOOGLE_UNKNOWN_FAILURE,
    GOOGLE_CANCELLED,
    GOOGLE_NO_ID_TOKEN,
    GOOGLE_EXCHANGE_FAILED,
    GOOGLE_NO_ACCOUNT,
    GOOGLE_PLAY_SERVICES,
    GITHUB_CONTEXT_UNAVAILABLE,
    GITHUB_FAILED,
    EMAIL_REQUIRED,
    EMAIL_INVALID,
    PASSWORD_TOO_SHORT,
    EMAIL_AUTH_FAILED,
}

internal object LoginFeedbackPolicy {
    fun googleProviderResult(
        wasCancelled: Boolean,
        hasIdToken: Boolean,
    ): LoginFeedback? = when {
        wasCancelled -> LoginFeedback.GOOGLE_CANCELLED
        !hasIdToken -> LoginFeedback.GOOGLE_NO_ID_TOKEN
        else -> null
    }

    fun googleApiFailure(
        wasCancelled: Boolean,
        statusCode: Int? = null,
    ): LoginFeedback = when {
        wasCancelled -> LoginFeedback.GOOGLE_CANCELLED
        else -> feedbackFor(GoogleSignInFailureClassifier.reasonForStatus(statusCode)
            ?: GoogleSignInFailureReason.UNKNOWN)
    }

    fun googleCredentialFailure(error: Throwable): LoginFeedback =
        feedbackFor(GoogleSignInFailureClassifier.classify(error))

    /** Classifies raw provider/Firebase diagnostics without exposing them in UI. */
    fun googleAuthFailure(message: String?): LoginFeedback {
        return feedbackFor(
            GoogleSignInFailureClassifier.classify(
                IllegalStateException(message.orEmpty())
            )
        )
    }

    private fun feedbackFor(reason: GoogleSignInFailureReason): LoginFeedback = when (reason) {
        GoogleSignInFailureReason.CANCELLED -> LoginFeedback.GOOGLE_CANCELLED
        GoogleSignInFailureReason.NO_ACCOUNT -> LoginFeedback.GOOGLE_NO_ACCOUNT
        GoogleSignInFailureReason.CLIENT_ID_MISSING -> LoginFeedback.GOOGLE_CLIENT_ID_MISSING
        GoogleSignInFailureReason.FIREBASE_CONFIGURATION -> LoginFeedback.GOOGLE_FIREBASE_CONFIG_MISSING
        GoogleSignInFailureReason.APP_MISCONFIGURED -> LoginFeedback.GOOGLE_APP_MISCONFIGURED
        GoogleSignInFailureReason.PROVIDER_DISABLED -> LoginFeedback.GOOGLE_PROVIDER_DISABLED
        GoogleSignInFailureReason.SIGNING_CERTIFICATE_NOT_REGISTERED -> LoginFeedback.GOOGLE_SHA_MISMATCH
        GoogleSignInFailureReason.NETWORK -> LoginFeedback.GOOGLE_NETWORK_FAILURE
        GoogleSignInFailureReason.PLAY_SERVICES_UNAVAILABLE -> LoginFeedback.GOOGLE_PLAY_SERVICES
        GoogleSignInFailureReason.CREDENTIAL_PROVIDER_UNAVAILABLE,
        GoogleSignInFailureReason.ACTIVITY_CONTEXT_REQUIRED -> LoginFeedback.GOOGLE_CREDENTIAL_MANAGER_FAILURE
        GoogleSignInFailureReason.NO_ID_TOKEN -> LoginFeedback.GOOGLE_NO_ID_TOKEN
        GoogleSignInFailureReason.INVALID_CREDENTIAL -> LoginFeedback.GOOGLE_EXCHANGE_FAILED
        GoogleSignInFailureReason.SECURE_STORAGE_UNAVAILABLE,
        GoogleSignInFailureReason.UNKNOWN -> LoginFeedback.GOOGLE_UNKNOWN_FAILURE
    }

    fun emailValidation(email: String, password: String): LoginFeedback? = when {
        email.isBlank() -> LoginFeedback.EMAIL_REQUIRED
        !email.contains("@") -> LoginFeedback.EMAIL_INVALID
        password.length < MINIMUM_PASSWORD_LENGTH -> LoginFeedback.PASSWORD_TOO_SHORT
        else -> null
    }

    const val MINIMUM_PASSWORD_LENGTH = 6
}
