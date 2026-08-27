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
