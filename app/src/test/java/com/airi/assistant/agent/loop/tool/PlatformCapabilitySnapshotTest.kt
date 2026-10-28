package com.airi.assistant.agent.loop.tool

import com.airi.assistant.ai.context.ContextBudget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlatformCapabilitySnapshotTest {
    @Test
    fun promptContainsAllRuntimeFamiliesAndKeepsOmittedStateDistinct() {
        val inventory = RuntimeCapabilityInventory(
            listOf(
                RuntimeCapabilityInventory.Entry(
                    id = "connector_google_gmail_read",
                    family = RuntimeCapabilityInventory.Family.CONNECTOR,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.AUTH_REQUIRED,
                    inRequestCatalog = true,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                    detail = "OAuth scope is missing",
                ),
                RuntimeCapabilityInventory.Entry(
                    id = "skill:research_agent",
                    family = RuntimeCapabilityInventory.Family.SKILL,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.READY,
                    inRequestCatalog = false,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                    detail = "Omitted by context budget",
                ),
            ),
        )
        val snapshot = PlatformCapabilitySnapshot.from(
            inventory = inventory,
            activeModelId = "local-model",
            activeProviderId = "local",
            contextBudget = ContextBudget.UNLOADED,
            agentEntries = listOf(
                PlatformCapabilitySnapshot.Entry("research_agent", PlatformCapabilitySnapshot.Family.AGENT, "registered", true, false, false)
            ),
            memoryEntries = listOf(
                PlatformCapabilitySnapshot.Entry("memory.semantic_retrieval", PlatformCapabilitySnapshot.Family.MEMORY, "not_ready", true, true, true)
            ),
        )
        val prompt = snapshot.promptBlock()
        assertTrue(prompt.contains("connector_google_gmail_read"))
        assertTrue(prompt.contains("auth_required"))
        assertTrue(prompt.contains("skill:research_agent"))
        assertTrue(prompt.contains("research_agent"))
        assertTrue(prompt.contains("memory.semantic_retrieval"))
        assertTrue(prompt.contains("exposed=false"))
        assertTrue(prompt.contains("OAuth scope is missing"))
    }

    @Test
    fun promptRedactsCredentialLikeValues() {
        val snapshot = PlatformCapabilitySnapshot(
            entries = listOf(
                PlatformCapabilitySnapshot.Entry(
                    id = "connector_github",
                    family = PlatformCapabilitySnapshot.Family.CONNECTOR,
                    status = "ready",
                    registered = true,
                    exposed = true,
                    admitted = true,
                    detail = "authorization=ghp_example_secret token=do-not-show",
                ),
            ),
        )
        val prompt = snapshot.promptBlock()
        assertFalse(prompt.contains("ghp_example_secret"))
        assertFalse(prompt.contains("do-not-show"))
        assertTrue(prompt.contains("[redacted]"))
    }

    @Test
    fun contextOmittedIsNotConfusedWithUnavailableOrUnregistered() {
        val inventory = RuntimeCapabilityInventory(
            listOf(
                RuntimeCapabilityInventory.Entry(
                    id = "connector_github_list_repos",
                    family = RuntimeCapabilityInventory.Family.CONNECTOR,
                    registered = true,
                    availability = RuntimeCapabilityInventory.Availability.CONTEXT_OMITTED,
                    inRequestCatalog = true,
                    exposedToAgent = false,
                    admittedToDispatcher = false,
                ),
            ),
        )
        val prompt = PlatformCapabilitySnapshot.from(inventory).promptBlock()
        assertTrue(prompt.contains("status=context_omitted"))
        assertTrue(prompt.contains("context_omitted=selected candidate removed"))
        assertTrue(prompt.contains("registered=true"))
        assertTrue(prompt.contains("exposed=false"))
    }
}
