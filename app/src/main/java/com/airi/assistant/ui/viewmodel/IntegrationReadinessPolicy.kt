package com.airi.assistant.ui.viewmodel

/** User-visible truthfulness state for an external integration. */
internal enum class IntegrationReadiness {
    DISCONNECTED,
    AUTHORIZATION_REQUIRED,
    READY
}

/** The explicit next user action for the Google connection button. */
internal enum class GoogleConnectionAction {
    START_IDENTITY_SIGN_IN,
    REQUEST_DATA_AUTHORIZATION,
    NONE
}

/**
 * Separates account identity from OAuth data authorization. An account visible
 * after sign-in does not prove it has a usable access token for Google APIs.
 */
internal object IntegrationReadinessPolicy {
    fun google(
        hasSignedInIdentity: Boolean,
        hasDataAccessToken: Boolean
    ): IntegrationReadiness = when {
        !hasSignedInIdentity -> IntegrationReadiness.DISCONNECTED
        !hasDataAccessToken -> IntegrationReadiness.AUTHORIZATION_REQUIRED
        else -> IntegrationReadiness.READY
    }

    fun credentialBacked(isConnected: Boolean): IntegrationReadiness =
        if (isConnected) IntegrationReadiness.READY else IntegrationReadiness.DISCONNECTED

    fun googleConnectionAction(readiness: IntegrationReadiness): GoogleConnectionAction = when (readiness) {
        IntegrationReadiness.DISCONNECTED -> GoogleConnectionAction.START_IDENTITY_SIGN_IN
        IntegrationReadiness.AUTHORIZATION_REQUIRED -> GoogleConnectionAction.REQUEST_DATA_AUTHORIZATION
        IntegrationReadiness.READY -> GoogleConnectionAction.NONE
    }
}
