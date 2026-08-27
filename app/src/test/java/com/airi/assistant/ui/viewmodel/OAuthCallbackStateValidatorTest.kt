package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OAuthCallbackStateValidatorTest {

    @Test
    fun acceptsOnlyAnExactNonBlankStateMatch() {
        assertTrue(OAuthCallbackStateValidator.matches("expected-state", "expected-state"))
        assertFalse(OAuthCallbackStateValidator.matches("expected-state", "different-state"))
    }

    @Test
    fun rejectsBlankExpectedOrIncomingState() {
        assertFalse(OAuthCallbackStateValidator.matches("", "incoming-state"))
        assertFalse(OAuthCallbackStateValidator.matches("expected-state", ""))
        assertFalse(OAuthCallbackStateValidator.matches(" ", " "))
    }
}
