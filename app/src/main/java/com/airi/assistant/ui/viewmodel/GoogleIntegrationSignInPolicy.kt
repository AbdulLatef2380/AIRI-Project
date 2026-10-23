package com.airi.assistant.ui.viewmodel

import androidx.annotation.StringRes
import com.airi.assistant.R
import com.airi.assistant.integrations.google.GoogleSignInFailureReason

/** Maps Google integration outcomes to safe, user-facing feedback. */
internal object GoogleIntegrationSignInPolicy {
    @StringRes
    fun cancelledFeedback(): Int = R.string.integration_google_sign_in_cancelled

    @StringRes
    fun providerFailureFeedback(): Int = R.string.integration_google_sign_in_failed

    @StringRes
    fun failureFeedback(reason: GoogleSignInFailureReason): Int = when (reason) {
        GoogleSignInFailureReason.CANCELLED -> cancelledFeedback()
        GoogleSignInFailureReason.NO_ACCOUNT -> R.string.login_google_no_account
        GoogleSignInFailureReason.CLIENT_ID_MISSING -> R.string.login_google_client_id_missing
        GoogleSignInFailureReason.FIREBASE_CONFIGURATION -> R.string.login_google_firebase_config_missing
        GoogleSignInFailureReason.APP_MISCONFIGURED -> R.string.login_google_app_misconfigured
        GoogleSignInFailureReason.PROVIDER_DISABLED -> R.string.login_google_provider_disabled
        GoogleSignInFailureReason.SIGNING_CERTIFICATE_NOT_REGISTERED -> R.string.login_google_sha_mismatch
        GoogleSignInFailureReason.NETWORK -> R.string.login_google_network_failure
        GoogleSignInFailureReason.PLAY_SERVICES_UNAVAILABLE -> R.string.login_google_play_services
        GoogleSignInFailureReason.CREDENTIAL_PROVIDER_UNAVAILABLE,
        GoogleSignInFailureReason.ACTIVITY_CONTEXT_REQUIRED -> R.string.login_google_credential_failure
        GoogleSignInFailureReason.NO_ID_TOKEN -> R.string.login_google_no_id_token
        GoogleSignInFailureReason.INVALID_CREDENTIAL -> R.string.login_google_exchange_failed
        GoogleSignInFailureReason.SECURE_STORAGE_UNAVAILABLE -> R.string.integration_google_storage_unavailable
        GoogleSignInFailureReason.UNKNOWN -> providerFailureFeedback()
    }

    @StringRes
    fun dataAuthorizationFailureFeedback(reason: GoogleSignInFailureReason): Int = when (reason) {
        GoogleSignInFailureReason.CANCELLED -> authorizationCancelledFeedback()
        GoogleSignInFailureReason.NO_ID_TOKEN -> authorizationFailedFeedback()
        GoogleSignInFailureReason.SECURE_STORAGE_UNAVAILABLE -> R.string.integration_google_storage_unavailable
        else -> failureFeedback(reason)
    }

    @StringRes
    fun missingEmailFeedback(): Int = R.string.integration_google_missing_email

    @StringRes
    fun connectedFeedback(): Int = R.string.integration_google_connected

    @StringRes
    fun dataAuthorizedFeedback(): Int = R.string.integration_google_data_authorized

    @StringRes
    fun authorizationCancelledFeedback(): Int = R.string.integration_google_authorization_cancelled

    @StringRes
    fun authorizationFailedFeedback(): Int = R.string.integration_google_authorization_failed

    fun canConnect(email: String?): Boolean = !email.isNullOrBlank()
}
