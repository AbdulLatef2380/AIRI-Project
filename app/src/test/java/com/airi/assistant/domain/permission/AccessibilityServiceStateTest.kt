package com.airi.assistant.domain.permission

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceStateTest {
    @Test
    fun matchesOnlyAnEnabledServiceFromTheRequestedPackage() {
        val enabled = "com.example.other/.Service:com.airi.assistant/.AiriAccessibilityService"

        assertTrue(AccessibilityServiceState.containsEnabledPackage(enabled, "com.airi.assistant"))
        assertFalse(AccessibilityServiceState.containsEnabledPackage(enabled, "com.airi"))
        assertFalse(AccessibilityServiceState.containsEnabledPackage(null, "com.airi.assistant"))
    }
}
