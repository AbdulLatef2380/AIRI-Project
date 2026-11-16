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

    @Test
    fun turnExposureSnapshotDistinguishesReadyBlockedAndContextOmitted() {
        val block = PlatformCapabilitySnapshot(emptyList()).turnExposureBlock(
            candidateToolNames = listOf("write_file", "terminal_execute", "connector_github_read"),
            permittedToolNames = listOf("write_file", "connector_github_read"),
            exposedToolNames = listOf("write_file"),
            omittedToolNames = listOf("connector_github_read"),
        )

        assertTrue(block.contains("write_file: registered=true; ready=true; exposed=true"))
        assertTrue(block.contains("terminal_execute: registered=true; ready=false; exposed=false"))
        assertTrue(block.contains("status=blocked_by_policy"))
        assertTrue(block.contains("connector_github_read: registered=true; ready=true; exposed=false"))
        assertTrue(block.contains("status=context_omitted"))
        assertTrue(block.contains("next_action=shorten_request_or_use_larger_context"))
    }

    @Test
    fun turnExposureSnapshotIsPresentWithoutPrebuiltPlatformSnapshot() {
        val block = PlatformCapabilitySnapshot(emptyList()).turnExposureBlock(
            candidateToolNames = listOf("write_file"),
            permittedToolNames = emptyList(),
            exposedToolNames = emptyList(),
            omittedToolNames = emptyList(),
        )

        assertTrue(block.startsWith("AIRI TURN TOOL EXPOSURE SNAPSHOT"))
        assertTrue(block.contains("registered=true"))
        assertTrue(block.contains("exposed=false"))
        assertTrue(block.contains("blocked_reason="))
    }
}
