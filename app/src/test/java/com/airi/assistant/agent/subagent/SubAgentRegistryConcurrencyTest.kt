package com.airi.assistant.agent.subagent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubAgentRegistryConcurrencyTest {
    @After
    fun cleanup() {
        SubAgentRegistry.resetForTests()
    }

    @Test
    fun concurrentReadsObserveOnlyCompleteSnapshots() = runBlocking {
        val agents = (0 until 32).map { FakeAgent("agent-$it") }
        SubAgentRegistry.initialize(agents)

        val snapshots = (0 until 100).map {
            async(Dispatchers.Default) {
                SubAgentRegistry.getAll().map { agent -> agent.capability.agentId }
            }
        }.awaitAll()

        assertTrue(snapshots.all { it.size == 32 })
        assertTrue(snapshots.all { it.toSet().size == 32 })
    }

    @Test
    fun explicitAndAutomaticRoutingShareTheSameAuthorizationGate() = runBlocking {
        val cloudAgent = FakeAgent(
            id = "cloud",
            capability = SubAgentCapability(
                agentId = "cloud",
                displayName = "Cloud",
                description = "cloud",
                intentKeywords = listOf("search"),
                requiredTools = listOf("cloud_browser"),
                requiresCloud = true,
            )
        )
        SubAgentRegistry.initialize(listOf(cloudAgent))
        val denied = SubAgentContext.test().copy(allowedTools = emptyList())

        assertNull(SubAgentRegistry.authorizedAgent("cloud", denied))
        assertNull(SubAgentRegistry.route("search", denied))

        val allowed = denied.copy(
            privacyLevel = SubAgentContext.PRIVACY_STANDARD,
            allowedTools = listOf("cloud_browser"),
        )
        assertNotNull(SubAgentRegistry.authorizedAgent("cloud", allowed))
        assertNotNull(SubAgentRegistry.route("search", allowed))
    }

    @Test
    fun freezePublishesAnImmutableRegistrationBoundary() {
        SubAgentRegistry.initialize(listOf(FakeAgent("before")))
        SubAgentRegistry.freeze()

        var threw = false
        try {
            SubAgentRegistry.register(FakeAgent("after"))
        } catch (_: IllegalStateException) {
            threw = true
        }

        assertTrue(threw)
        assertEquals(listOf("before"), SubAgentRegistry.getAll().map { it.capability.agentId })
    }

    private class FakeAgent(
        id: String,
        override val capability: SubAgentCapability = SubAgentCapability(
            agentId = id,
            displayName = id,
            description = id,
            intentKeywords = listOf(id),
            costTier = SubAgentCapability.CostTier.FREE,
        ),
    ) : SubAgent {
        override suspend fun canHandle(input: String, context: SubAgentContext): Boolean = true
        override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = emptyFlow()
    }
}
