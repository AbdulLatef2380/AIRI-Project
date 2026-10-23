package com.airi.assistant.integrations.google

import com.airi.assistant.integrations.google.GoogleSignInFailureReason.*
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GoogleSignInFailureClassifierTest {
    @Test
    fun mapsOnlyWellKnownGoogleStatusCodesToSpecificCauses() {
        assertEquals(NETWORK, GoogleSignInFailureClassifier.reasonForStatus(7))
        assertEquals(NETWORK, GoogleSignInFailureClassifier.reasonForStatus(15))
        assertEquals(APP_MISCONFIGURED, GoogleSignInFailureClassifier.reasonForStatus(10))
        assertEquals(CANCELLED, GoogleSignInFailureClassifier.reasonForStatus(16))
        assertEquals(PLAY_SERVICES_UNAVAILABLE, GoogleSignInFailureClassifier.reasonForStatus(2))
        assertEquals(null, GoogleSignInFailureClassifier.reasonForStatus(8))
    }

    @Test
    fun classifiesConnectorErrorCodesWithoutInspectingProviderText() {
        assertEquals(APP_MISCONFIGURED, GoogleSignInFailureClassifier.classifyCode("google_app_misconfigured"))
        assertEquals(
            SIGNING_CERTIFICATE_NOT_REGISTERED,
            GoogleSignInFailureClassifier.classifyCode("google_signing_certificate_not_registered"),
        )
        assertEquals(NETWORK, GoogleSignInFailureClassifier.classifyCode("google_network"))
        assertEquals(
            ACTIVITY_CONTEXT_REQUIRED,
            GoogleSignInFailureClassifier.classifyCode("google_activity_context_required"),
        )
    }

    @Test
    fun acceptsOnlyAWellFormedWebOauthClientId() {
        assertTrue(GoogleWebClientIdPolicy.isValid("1234567890-abc.apps.googleusercontent.com"))
        assertFalse(GoogleWebClientIdPolicy.isValid(""))
        assertFalse(GoogleWebClientIdPolicy.isValid("REPLACE_WITH_CLIENT_ID"))
        assertFalse(GoogleWebClientIdPolicy.isValid("1234567890-abc.example.com"))
    }

    @Test
    fun normalizesUnderscoreBasedProviderDiagnostics() {
        assertEquals(
            FIREBASE_CONFIGURATION,
            GoogleSignInFailureClassifier.classifyCode("ERROR_INVALID_API_KEY"),
        )
        assertEquals(
            CLIENT_ID_MISSING,
            GoogleSignInFailureClassifier.classify(IllegalStateException("default_web_client_id is missing")),
        )
        assertEquals(
            PLAY_SERVICES_UNAVAILABLE,
            GoogleSignInFailureClassifier.classify(IllegalStateException("SERVICE_VERSION_UPDATE_REQUIRED")),
        )
    }
}
