package com.airi.assistant.ui.viewmodel

/** User-visible truthfulness state for an external integration. */
internal enum class IntegrationReadiness {
    DISCONNECTED,
    AUTHORIZATION_REQUIRED,
    READY
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
}
