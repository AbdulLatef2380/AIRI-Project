package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCapabilityInventoryTest {
    @Test
    fun googleConnectedWithGrantedGmailScopeIsReady() {
        assertEquals(
            RuntimeCapabilityInventory.Availability.READY,
            RuntimeCapabilityInventory.connectorAvailability(
                RuntimeCapabilityInventory.ConnectorReadinessFacts(
                    registered = true,
                    hasActions = true,
                    hasPermittedActions = true,
                    connected = true,
                    healthy = true,
                    requiredProviderScopesGranted = true,
                )
            )
        )
    }

    @Test
    fun connectedGoogleWithoutGmailScopeRequiresAuthorizationNotAccessibility() {
        assertEquals(
            RuntimeCapabilityInventory.Availability.AUTH_REQUIRED,
            RuntimeCapabilityInventory.connectorAvailability(
                RuntimeCapabilityInventory.ConnectorReadinessFacts(
                    registered = true,
                    hasActions = true,
                    hasPermittedActions = true,
                    connected = true,
                    healthy = true,
                    requiredProviderScopesGranted = false,
                )
            )
        )
    }

    @Test
    fun connectedProviderWithoutAiriAccessGrantRequiresPermission() {
        assertEquals(
            RuntimeCapabilityInventory.Availability.PERMISSION_REQUIRED,
            RuntimeCapabilityInventory.connectorAvailability(
                RuntimeCapabilityInventory.ConnectorReadinessFacts(
                    registered = true,
                    hasActions = true,
                    hasPermittedActions = false,
                    connected = true,
                    healthy = true,
                    requiredProviderScopesGranted = true,
                )
            )
        )
    }

    @Test
    fun disconnectedProviderIsNotReportedAsMissingImplementation() {
        assertEquals(
            RuntimeCapabilityInventory.Availability.DISCONNECTED,
            RuntimeCapabilityInventory.connectorAvailability(
                RuntimeCapabilityInventory.ConnectorReadinessFacts(
                    registered = true,
                    hasActions = true,
                    hasPermittedActions = true,
                    connected = false,
                    healthy = false,
                )
            )
        )
    }

    @Test
    fun unsupportedAndUnregisteredSurfacesStayDistinct() {
        assertEquals(
            RuntimeCapabilityInventory.Availability.UNSUPPORTED,
            RuntimeCapabilityInventory.connectorAvailability(
                RuntimeCapabilityInventory.ConnectorReadinessFacts(
                    supported = false,
                    registered = false,
                    hasActions = false,
                    hasPermittedActions = false,
                    connected = false,
                    healthy = false,
                )
            )
        )
        assertEquals(
            RuntimeCapabilityInventory.Availability.CONFIGURATION_REQUIRED,
            RuntimeCapabilityInventory.connectorAvailability(
                RuntimeCapabilityInventory.ConnectorReadinessFacts(
                    registered = false,
                    hasActions = false,
                    hasPermittedActions = false,
                    connected = false,
                    healthy = false,
                )
            )
        )
    }

    @Test
    fun genericDiscoveryPromptReportsSkillsConnectorsSandboxAndTerminalFromSnapshot() {
        val inventory = RuntimeCapabilityInventory(
            listOf(
                RuntimeCapabilityInventory.Entry(
                    id = "skill:research_agent",
                    family = RuntimeCapabilityInventory.Family.SKILL,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.DEPENDENCY_MISSING,
                    inRequestCatalog = false,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                    detail = "Missing connector dependency: github.",
                ),
                RuntimeCapabilityInventory.Entry(
                    id = "google_gmail",
                    family = RuntimeCapabilityInventory.Family.CONNECTOR,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.AUTH_REQUIRED,
                    inRequestCatalog = true,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                ),
                RuntimeCapabilityInventory.Entry(
                    id = "terminal_execute",
                    family = RuntimeCapabilityInventory.Family.BUILTIN,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.BLOCKED,
                    inRequestCatalog = false,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                    detail = "Terminal policy disabled.",
                ),
                RuntimeCapabilityInventory.Entry(
                    id = "sandbox_process_execution",
                    family = RuntimeCapabilityInventory.Family.OTHER,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.BLOCKED,
                    inRequestCatalog = false,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                    detail = "No process execution path is enabled.",
                ),
            )
        )

        val prompt = inventory.promptBlock("What skills, connectors, Sandbox and Terminal are available?")
        assertTrue(prompt.contains("skill:research_agent"))
        assertTrue(prompt.contains("google_gmail"))
        assertTrue(prompt.contains("terminal_execute"))
        assertTrue(prompt.contains("sandbox_process_execution"))
        assertTrue(prompt.contains("dependency_missing"))
        assertTrue(prompt.contains("auth_required"))
        assertTrue(prompt.contains("blocked"))
    }

    @Test
    fun traceProjectionPreservesRequestStateAndDoesNotInventExecution() {
        val inventory = RuntimeCapabilityInventory(
            listOf(
                RuntimeCapabilityInventory.Entry(
                    id = "google_gmail",
                    family = RuntimeCapabilityInventory.Family.CONNECTOR,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.AUTH_REQUIRED,
                    inRequestCatalog = true,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                )
            )
        )

        val trace = inventory.traceEntries().single()
        assertEquals("google_gmail", trace.id)
        assertEquals(false, trace.authenticated)
        assertEquals(false, trace.executable)
        assertEquals(true, trace.selected)
        assertEquals(false, trace.exposed)
        assertEquals(null, trace.executed)
        assertEquals(null, trace.resultReturned)
    }
}
