package com.airi.assistant.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityProfileStabilityTest {
    @Test
    fun rejectsPromptThatExceedsLocalContextBeforeDispatch() {
        val profile = CapabilityProfile(maxContextTokens = 1_536)
        val request = ExecutionRequest(
            prompt = "large",
            estimatedPromptTokens = 1_300,
            maxTokens = 256,
        )

        assertFalse(profile.canFit(request))
        assertFalse(profile.satisfies(request))
    }

    @Test
    fun acceptsRequestThatFitsWithSafetyReserve() {
        val profile = CapabilityProfile(
            supportsStreaming = true,
            maxContextTokens = 2_048,
        )
        val request = ExecutionRequest(
            prompt = "small",
            estimatedPromptTokens = 800,
            maxTokens = 512,
        )

        assertTrue(profile.canFit(request))
        assertTrue(profile.satisfies(request))
    }

    @Test
    fun accountsForVisionPayloadInsteadOfTreatingBase64AsFree() {
        val profile = CapabilityProfile(
            supportsVision = true,
            maxContextTokens = 1_024,
        )
        val request = ExecutionRequest(
            prompt = "describe",
            requiresVision = true,
            estimatedPromptTokens = 500,
            maxTokens = 256,
            imageParts = listOf(
                ExecutionRequest.ImagePart("image/png", "A".repeat(8_192))
            ),
        )

        assertFalse(profile.canFit(request))
    }
}
