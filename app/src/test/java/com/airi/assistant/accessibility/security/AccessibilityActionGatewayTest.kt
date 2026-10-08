package com.airi.assistant.accessibility.security

import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityActionGatewayTest {
    @Test fun unknownPackageIsDenied() {
        assertTrue(AccessibilityActionGateway.authorize("", "read_screen") is AccessibilityActionGateway.Decision.Denied)
        assertTrue(AccessibilityActionGateway.authorize("unknown", "tap") is AccessibilityActionGateway.Decision.Denied)
    }

    @Test fun sensitivePackageIsDeniedForReadsAndActions() {
        assertTrue(AccessibilityActionGateway.authorize("com.android.settings", "read_screen") is AccessibilityActionGateway.Decision.Denied)
        assertTrue(AccessibilityActionGateway.authorize("com.chase.sig.android", "tap", "Continue") is AccessibilityActionGateway.Decision.Denied)
    }

    @Test fun safeReadAndNavigationAreAllowed() {
        assertTrue(AccessibilityActionGateway.authorize("com.example.notes", "read_screen") is AccessibilityActionGateway.Decision.Allowed)
        assertTrue(AccessibilityActionGateway.authorize("com.example.notes", "scroll_down") is AccessibilityActionGateway.Decision.Allowed)
    }

    @Test fun destructiveActionsRequireApprovalRatherThanAllowingDirectDispatch() {
        assertTrue(AccessibilityActionGateway.authorize("com.example.mail", "click", "Send message") is AccessibilityActionGateway.Decision.NeedsConfirmation)
        assertTrue(AccessibilityActionGateway.authorize("com.example.mail", "delete_item") is AccessibilityActionGateway.Decision.NeedsConfirmation)
        assertTrue(AccessibilityActionGateway.authorize("com.example.mail", "click", "احذف الحساب") is AccessibilityActionGateway.Decision.NeedsConfirmation)
    }
}
