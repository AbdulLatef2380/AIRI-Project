package com.airi.assistant.ui.screens

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
    GOOGLE_CREDENTIAL_MANAGER_FAILURE,
    GOOGLE_NETWORK_FAILURE,
    GOOGLE_UNKNOWN_FAILURE,
    GOOGLE_CANCELLED,
    GOOGLE_NO_ID_TOKEN,
    GOOGLE_EXCHANGE_FAILED,
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
        statusCode == 10 -> LoginFeedback.GOOGLE_SHA_MISMATCH // DEVELOPER_ERROR
        statusCode == 8 -> LoginFeedback.GOOGLE_NETWORK_FAILURE // INTERNAL_ERROR/network path
        else -> LoginFeedback.GOOGLE_CREDENTIAL_MANAGER_FAILURE
    }

    /** Classifies raw provider/Firebase diagnostics without exposing them in UI. */
    fun googleAuthFailure(message: String?): LoginFeedback {
        val normalized = message.orEmpty().lowercase()
        return when {
            normalized.isBlank() -> LoginFeedback.GOOGLE_UNKNOWN_FAILURE
            normalized.containsAny("network", "timeout", "unavailable") ->
                LoginFeedback.GOOGLE_NETWORK_FAILURE
            normalized.containsAny("operation-not-allowed", "provider disabled", "provider is disabled") ->
                LoginFeedback.GOOGLE_PROVIDER_DISABLED
            normalized.containsAny("sha", "fingerprint", "developer error", "status code: 10") ->
                LoginFeedback.GOOGLE_SHA_MISMATCH
            normalized.containsAny("firebaseapp", "firebase app", "google-services", "configuration") ->
                LoginFeedback.GOOGLE_FIREBASE_CONFIG_MISSING
            normalized.containsAny("credential manager", "credentialmanager", "credential provider") ->
                LoginFeedback.GOOGLE_CREDENTIAL_MANAGER_FAILURE
            normalized.containsAny("client id", "server client", "oauth client") ->
                LoginFeedback.GOOGLE_CLIENT_ID_MISSING
            else -> LoginFeedback.GOOGLE_UNKNOWN_FAILURE
        }
    }

    private fun String.containsAny(vararg values: String): Boolean = values.any(::contains)

    fun emailValidation(email: String, password: String): LoginFeedback? = when {
        email.isBlank() -> LoginFeedback.EMAIL_REQUIRED
        !email.contains("@") -> LoginFeedback.EMAIL_INVALID
        password.length < MINIMUM_PASSWORD_LENGTH -> LoginFeedback.PASSWORD_TOO_SHORT
        else -> null
    }

    const val MINIMUM_PASSWORD_LENGTH = 6
}
