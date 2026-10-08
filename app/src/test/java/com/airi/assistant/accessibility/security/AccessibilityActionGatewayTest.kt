package com.airi.assistant.accessibility.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityActionGatewayTest {
    @Test
    fun protectedPackageIsDeniedBeforeScreenRead() {
        val decision = AccessibilityActionGateway.authorize(
            "com.android.settings",
            AccessibilityActionGateway.Action.READ_SCREEN
        )
        assertTrue(decision is AccessibilityActionGateway.Decision.Deny)
    }

    @Test
    fun blankPackageIsDeniedWithoutReadingScreen() {
        val decision = AccessibilityActionGateway.authorize(
            "",
            AccessibilityActionGateway.Action.READ_SCREEN
        )
        assertEquals(
            AccessibilityActionGateway.Decision.Deny("active package is unavailable"),
            decision
        )
    }

    @Test
    fun destructiveAccessibilityActionNeedsConfirmation() {
        val decision = AccessibilityActionGateway.authorize(
            "com.example.mail",
            AccessibilityActionGateway.Action.TAP,
            payload = "send message"
        )
        assertTrue(decision is AccessibilityActionGateway.Decision.NeedsConfirmation)
    }

    @Test
    fun confirmedDestructiveActionIsAllowedForUnprotectedPackage() {
        val decision = AccessibilityActionGateway.authorize(
            "com.example.mail",
            AccessibilityActionGateway.Action.TAP,
            payload = "send message",
            confirmed = true
        )
        assertEquals(AccessibilityActionGateway.Decision.Allow, decision)
    }
}
