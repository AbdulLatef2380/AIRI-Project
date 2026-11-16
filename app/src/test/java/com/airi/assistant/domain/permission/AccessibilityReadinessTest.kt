package com.airi.assistant.domain.permission

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityReadinessTest {
    @Test
    fun installedIsNotEnoughToAdmitTheAgent() {
        assertEquals(
            AccessibilityReadiness.Status.DISABLED,
            AccessibilityReadiness.deriveStatus(installed = true, enabled = false, connected = false),
        )
    }

    @Test
    fun enabledButDisconnectedWaitsForServiceLifecycle() {
        assertEquals(
            AccessibilityReadiness.Status.ENABLED_NOT_CONNECTED,
            AccessibilityReadiness.deriveStatus(installed = true, enabled = true, connected = false),
        )
    }

    @Test
    fun onlyEnabledAndConnectedIsReady() {
        val status = AccessibilityReadiness.deriveStatus(true, true, true)
        assertEquals(AccessibilityReadiness.Status.READY, status)
        assertTrue(status == AccessibilityReadiness.Status.READY)
    }

    @Test
    fun missingDeclarationStaysNotInstalledEvenIfStaleFlagsClaimEnabled() {
        val status = AccessibilityReadiness.deriveStatus(false, true, true)
        assertEquals(AccessibilityReadiness.Status.NOT_INSTALLED, status)
        assertFalse(status == AccessibilityReadiness.Status.READY)
    }
}
