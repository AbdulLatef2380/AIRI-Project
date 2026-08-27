package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleIntegrationSignInPolicyTest {
    @Test
    fun acceptsOnlyAccountsWithAnEmailAddress() {
        assertTrue(GoogleIntegrationSignInPolicy.canConnect("user@example.com"))
        assertFalse(GoogleIntegrationSignInPolicy.canConnect(null))
        assertFalse(GoogleIntegrationSignInPolicy.canConnect(""))
    }

    @Test
    fun exposesDistinctFeedbackForNonSuccessfulOutcomes() {
        assertNotEquals(
            GoogleIntegrationSignInPolicy.cancelledFeedback(),
            GoogleIntegrationSignInPolicy.providerFailureFeedback()
        )
        assertNotEquals(
            GoogleIntegrationSignInPolicy.providerFailureFeedback(),
            GoogleIntegrationSignInPolicy.missingEmailFeedback()
        )
        assertNotEquals(
            GoogleIntegrationSignInPolicy.connectedFeedback(),
            GoogleIntegrationSignInPolicy.cancelledFeedback()
        )
    }
}
