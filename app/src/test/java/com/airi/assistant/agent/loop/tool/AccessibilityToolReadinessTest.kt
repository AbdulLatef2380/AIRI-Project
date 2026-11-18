package com.airi.assistant.agent.loop.tool

import com.airi.assistant.domain.permission.AccessibilityReadiness
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AccessibilityToolReadinessTest {
    @Test
    fun disabledServiceIsNotExposedAsReadyAndSuggestsEnable() {
        val override = AccessibilityToolReadiness.override(
            AccessibilityReadiness.Snapshot(
                status = AccessibilityReadiness.Status.DISABLED,
                installed = true,
                enabled = false,
                connected = false,
                agentAdmitted = false,
                nextAction = AccessibilityReadiness.NextAction.OPEN_ACCESSIBILITY_SETTINGS,
            ),
        )

        requireNotNull(override)
        assertEquals(RuntimeToolContract.Readiness.PERMISSION_REQUIRED, override.readiness)
        assertEquals("enable_accessibility", override.nextAction)
    }

    @Test
    fun enabledButDisconnectedServiceSuggestsWaitingForConnection() {
        val override = AccessibilityToolReadiness.override(
            AccessibilityReadiness.Snapshot(
                status = AccessibilityReadiness.Status.ENABLED_NOT_CONNECTED,
                installed = true,
                enabled = true,
                connected = false,
                agentAdmitted = false,
                nextAction = AccessibilityReadiness.NextAction.WAIT_FOR_SERVICE_CONNECTION,
            ),
        )

        requireNotNull(override)
        assertEquals("wait_for_accessibility_connection", override.nextAction)
    }

    @Test
    fun connectedServiceHasNoOverride() {
        val override = AccessibilityToolReadiness.override(
            AccessibilityReadiness.Snapshot(
                status = AccessibilityReadiness.Status.READY,
                installed = true,
                enabled = true,
                connected = true,
                agentAdmitted = true,
                nextAction = AccessibilityReadiness.NextAction.NONE,
            ),
        )

        assertNull(override)
    }
}
