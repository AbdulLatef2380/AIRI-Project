package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Test

class IntegrationReadinessPolicyTest {

    @Test
    fun googleAccountWithoutDataTokenRequiresAuthorization() {
        assertEquals(
            IntegrationReadiness.AUTHORIZATION_REQUIRED,
            IntegrationReadinessPolicy.google(
                hasSignedInIdentity = true,
                hasDataAccessToken = false
            )
        )
    }

    @Test
    fun googleWithoutIdentityIsDisconnectedEvenWhenAStaleTokenIsPresent() {
        assertEquals(
            IntegrationReadiness.DISCONNECTED,
            IntegrationReadinessPolicy.google(
                hasSignedInIdentity = false,
                hasDataAccessToken = true
            )
        )
    }

    @Test
    fun googleIsReadyOnlyWithIdentityAndDataAccessToken() {
        assertEquals(
            IntegrationReadiness.READY,
            IntegrationReadinessPolicy.google(
                hasSignedInIdentity = true,
                hasDataAccessToken = true
            )
        )
    }

    @Test
    fun googleConnectionActionStartsIdentitySignInOnlyWhenDisconnected() {
        assertEquals(
            GoogleConnectionAction.START_IDENTITY_SIGN_IN,
            IntegrationReadinessPolicy.googleConnectionAction(IntegrationReadiness.DISCONNECTED)
        )
    }

    @Test
    fun googleConnectionActionRequestsDataAuthorizationForSignedInIdentity() {
        assertEquals(
            GoogleConnectionAction.REQUEST_DATA_AUTHORIZATION,
            IntegrationReadinessPolicy.googleConnectionAction(IntegrationReadiness.AUTHORIZATION_REQUIRED)
        )
    }

    @Test
    fun googleConnectionActionIsNoOpWhenAlreadyReady() {
        assertEquals(
            GoogleConnectionAction.NONE,
            IntegrationReadinessPolicy.googleConnectionAction(IntegrationReadiness.READY)
        )
    }

    @Test
    fun credentialBackedIntegrationHasOnlyReadyOrDisconnectedStates() {
        assertEquals(
            IntegrationReadiness.READY,
            IntegrationReadinessPolicy.credentialBacked(true)
        )
        assertEquals(
            IntegrationReadiness.DISCONNECTED,
            IntegrationReadinessPolicy.credentialBacked(false)
        )
    }
}
