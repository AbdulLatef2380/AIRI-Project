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

    fun googleApiFailure(wasCancelled: Boolean): LoginFeedback =
        if (wasCancelled) LoginFeedback.GOOGLE_CANCELLED else LoginFeedback.GOOGLE_EXCHANGE_FAILED

    fun emailValidation(email: String, password: String): LoginFeedback? = when {
        email.isBlank() -> LoginFeedback.EMAIL_REQUIRED
        !email.contains("@") -> LoginFeedback.EMAIL_INVALID
        password.length < MINIMUM_PASSWORD_LENGTH -> LoginFeedback.PASSWORD_TOO_SHORT
        else -> null
    }

    const val MINIMUM_PASSWORD_LENGTH = 6
}
