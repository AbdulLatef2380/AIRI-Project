package com.airi.assistant.ui.viewmodel

import androidx.annotation.StringRes
import com.airi.assistant.R

/** Maps Google integration outcomes to safe, user-facing feedback. */
internal object GoogleIntegrationSignInPolicy {
    @StringRes
    fun cancelledFeedback(): Int = R.string.integration_google_sign_in_cancelled

    @StringRes
    fun providerFailureFeedback(): Int = R.string.integration_google_sign_in_failed

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
