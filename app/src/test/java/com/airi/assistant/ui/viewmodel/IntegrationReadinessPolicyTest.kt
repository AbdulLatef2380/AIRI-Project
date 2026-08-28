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
