package com.airi.assistant.agent.subagent

import org.junit.Assert.assertTrue
import org.junit.Test

class MediaGenerationAdmissionTest {
    private val capability = SubAgentCapability(
        agentId = "media_generation_agent",
        displayName = "Media Generation",
        description = "Generate AI images from text descriptions.",
        intentKeywords = listOf("generate image", "create image", "draw"),
        domains = listOf("image", "media", "generation"),
        requiresCloud = true,
        requiredTools = listOf("image_generator"),
        costTier = SubAgentCapability.CostTier.HIGH,
        latencyProfile = SubAgentCapability.LatencyProfile.SLOW,
    )

    @Test
    fun maximumPrivacyBlocksTextToImageBeforeAnyProviderCall() {
        val decision = SubAgentAuthorization.evaluate(
            capability = capability,
            context = SubAgentContext.test().copy(
                privacyLevel = SubAgentContext.PRIVACY_MAXIMUM,
                allowedTools = listOf("image_generator"),
            ),
        )
        assertTrue(decision is SubAgentAuthorization.Decision.Deny)
        assertTrue((decision as SubAgentAuthorization.Decision.Deny).reason.contains("cloud"))
    }

    @Test
    fun cloudGenerationRequiresItsDeclaredToolAndBudget() {
        val decision = SubAgentAuthorization.evaluate(
            capability = capability,
            context = SubAgentContext.test().copy(
                privacyLevel = SubAgentContext.PRIVACY_BALANCED,
                allowedTools = listOf("image_generator"),
                remainingCloudTokenBudget = 50_000,
            ),
        )
        assertTrue(decision is SubAgentAuthorization.Decision.Allow)
    }
}
