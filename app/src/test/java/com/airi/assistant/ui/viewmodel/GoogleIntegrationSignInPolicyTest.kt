package com.airi.assistant.ui.viewmodel

import com.airi.assistant.R
import com.airi.assistant.integrations.google.GoogleSignInFailureReason
import org.junit.Assert.assertEquals
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

    @Test
    fun mapsTypedIdentityAndConsentFailuresToLocalizedResourceKeys() {
        assertEquals(
            R.string.login_google_no_account,
            GoogleIntegrationSignInPolicy.failureFeedback(GoogleSignInFailureReason.NO_ACCOUNT),
        )
        assertEquals(
            R.string.login_google_app_misconfigured,
            GoogleIntegrationSignInPolicy.failureFeedback(GoogleSignInFailureReason.APP_MISCONFIGURED),
        )
        assertEquals(
            R.string.integration_google_storage_unavailable,
            GoogleIntegrationSignInPolicy.dataAuthorizationFailureFeedback(
                GoogleSignInFailureReason.SECURE_STORAGE_UNAVAILABLE,
            ),
        )
        assertEquals(
            R.string.integration_google_authorization_cancelled,
            GoogleIntegrationSignInPolicy.dataAuthorizationFailureFeedback(GoogleSignInFailureReason.CANCELLED),
        )
    }
}
