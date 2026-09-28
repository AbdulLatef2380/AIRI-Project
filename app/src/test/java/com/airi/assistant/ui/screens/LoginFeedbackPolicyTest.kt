package com.airi.assistant.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoginFeedbackPolicyTest {

    @Test
    fun cancelledGoogleResultRemainsAnExplicitCancellation() {
        assertEquals(
            LoginFeedback.GOOGLE_CANCELLED,
            LoginFeedbackPolicy.googleProviderResult(wasCancelled = true, hasIdToken = true),
        )
        assertEquals(
            LoginFeedback.GOOGLE_CANCELLED,
            LoginFeedbackPolicy.googleApiFailure(wasCancelled = true),
        )
    }

    @Test
    fun successfulGoogleResultRequiresAnIdToken() {
        assertEquals(
            LoginFeedback.GOOGLE_NO_ID_TOKEN,
            LoginFeedbackPolicy.googleProviderResult(wasCancelled = false, hasIdToken = false),
        )
        assertNull(LoginFeedbackPolicy.googleProviderResult(wasCancelled = false, hasIdToken = true))
    }

    @Test
    fun googleApiStatusCodesKeepConfigurationAndNetworkCausesDistinct() {
        assertEquals(
            LoginFeedback.GOOGLE_SHA_MISMATCH,
            LoginFeedbackPolicy.googleApiFailure(wasCancelled = false, statusCode = 10),
        )
        assertEquals(
            LoginFeedback.GOOGLE_NETWORK_FAILURE,
            LoginFeedbackPolicy.googleApiFailure(wasCancelled = false, statusCode = 8),
        )
        assertEquals(
            LoginFeedback.GOOGLE_CREDENTIAL_MANAGER_FAILURE,
            LoginFeedbackPolicy.googleApiFailure(wasCancelled = false, statusCode = 13),
        )
    }

    @Test
    fun googleAuthMessagesAreClassifiedWithoutExposingRawProviderText() {
        assertEquals(
            LoginFeedback.GOOGLE_PROVIDER_DISABLED,
            LoginFeedbackPolicy.googleAuthFailure("ERROR_OPERATION_NOT_ALLOWED: provider disabled"),
        )
        assertEquals(
            LoginFeedback.GOOGLE_FIREBASE_CONFIG_MISSING,
            LoginFeedbackPolicy.googleAuthFailure("FirebaseApp is not initialized"),
        )
        assertEquals(
            LoginFeedback.GOOGLE_NETWORK_FAILURE,
            LoginFeedbackPolicy.googleAuthFailure("network request timed out"),
        )
    }

    @Test
    fun emailValidationHasSpecificUserSafeOutcomes() {
        assertEquals(
            LoginFeedback.EMAIL_REQUIRED,
            LoginFeedbackPolicy.emailValidation(email = "", password = "password"),
        )
        assertEquals(
            LoginFeedback.EMAIL_INVALID,
            LoginFeedbackPolicy.emailValidation(email = "airi", password = "password"),
        )
        assertEquals(
            LoginFeedback.PASSWORD_TOO_SHORT,
            LoginFeedbackPolicy.emailValidation(email = "airi@example.com", password = "12345"),
        )
        assertNull(LoginFeedbackPolicy.emailValidation(email = "airi@example.com", password = "123456"))
    }
}
