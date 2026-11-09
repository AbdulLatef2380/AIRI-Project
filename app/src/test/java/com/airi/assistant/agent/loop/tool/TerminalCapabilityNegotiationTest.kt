package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalCapabilityNegotiationTest {
    @Test
    fun missingTaskWorkspaceIsVisibleAsConfigurationRequiredNotAbsent() {
        val projection = TerminalCapabilityNegotiation.project(
            registered = true,
            sandboxReady = false,
            policyAllowed = true,
            requested = true,
            exposed = true,
            admitted = true,
        )

        assertEquals(RuntimeCapabilityInventory.Availability.CONFIGURATION_REQUIRED, projection.availability)
        assertTrue(projection.inRequestCatalog)
        assertFalse(projection.exposedToAgent)
        assertTrue(projection.detail.contains("task-owned"))
    }

    @Test
    fun blockedPolicyRemainsVisibleButNeverExecutable() {
        val projection = TerminalCapabilityNegotiation.project(true, true, false, true, true, true)

        assertEquals(RuntimeCapabilityInventory.Availability.BLOCKED, projection.availability)
        assertFalse(projection.exposedToAgent)
        assertFalse(projection.admittedToDispatcher)
        assertTrue(projection.detail.contains("blocked"))
    }

    @Test
    fun readyTerminalKeepsGatewayRouteAndRequestSelection() {
        val projection = TerminalCapabilityNegotiation.project(true, true, true, true, true, true)

        assertEquals(RuntimeCapabilityInventory.Availability.READY, projection.availability)
        assertTrue(projection.inRequestCatalog)
        assertTrue(projection.exposedToAgent)
        assertTrue(projection.admittedToDispatcher)
    }
}
