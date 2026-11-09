package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCapabilityInventoryLifecycleTest {

    @Test
    fun executableEntryProjectsEveryLifecycleStageInOrder() {
        val entry = RuntimeCapabilityInventory.Entry(
            id = "github_read_file",
            family = RuntimeCapabilityInventory.Family.CONNECTOR,
            registered = true,
            availability = RuntimeCapabilityInventory.Availability.READY,
            inRequestCatalog = true,
            exposedToAgent = true,
            admittedToDispatcher = true,
            dispatcherRoute = "ConnectorToolBridge",
        )

        assertEquals(
            listOf(
                RuntimeCapabilityInventory.LifecycleStage.REGISTERED,
                RuntimeCapabilityInventory.LifecycleStage.READY,
                RuntimeCapabilityInventory.LifecycleStage.SELECTED,
                RuntimeCapabilityInventory.LifecycleStage.EXPOSED,
                RuntimeCapabilityInventory.LifecycleStage.ADMITTED,
                RuntimeCapabilityInventory.LifecycleStage.DISPATCHER_ROUTE,
                RuntimeCapabilityInventory.LifecycleStage.EXECUTABLE,
            ),
            entry.lifecycle.stages,
        )
        assertEquals(RuntimeCapabilityInventory.LifecycleStage.EXECUTABLE, entry.lifecycle.current)
    }

    @Test
    fun unavailableEntryStopsAtReadinessAndPreservesReason() {
        val entry = RuntimeCapabilityInventory.Entry(
            id = "gmail_read",
            family = RuntimeCapabilityInventory.Family.CONNECTOR,
            registered = true,
            availability = RuntimeCapabilityInventory.Availability.AUTH_REQUIRED,
            inRequestCatalog = true,
            exposedToAgent = false,
            admittedToDispatcher = false,
            detail = "OAuth scope is missing",
            dispatcherRoute = "ConnectorToolBridge",
        )

        assertEquals(
            listOf(RuntimeCapabilityInventory.LifecycleStage.REGISTERED),
            entry.lifecycle.stages,
        )
        assertEquals(RuntimeCapabilityInventory.LifecycleStage.REGISTERED, entry.lifecycle.current)
        assertEquals("OAuth scope is missing", entry.lifecycle.reason)
    }

    @Test
    fun promptBlockExplainsLifecycleInsteadOfClaimingCapabilityIsAbsent() {
        val inventory = RuntimeCapabilityInventory(
            listOf(
                RuntimeCapabilityInventory.Entry(
                    id = "github_read_file",
                    family = RuntimeCapabilityInventory.Family.CONNECTOR,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.PERMISSION_REQUIRED,
                    inRequestCatalog = true,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                    detail = "Grant the repository read profile",
                    dispatcherRoute = "ConnectorToolBridge",
                ),
            ),
        )

        val prompt = inventory.promptBlock("read github_read_file")

        assertTrue(prompt.contains("lifecycle=registered"))
        assertTrue(prompt.contains("Grant the repository read profile"))
        assertTrue(prompt.contains("Neither state means the product feature is absent"))
    }
}
