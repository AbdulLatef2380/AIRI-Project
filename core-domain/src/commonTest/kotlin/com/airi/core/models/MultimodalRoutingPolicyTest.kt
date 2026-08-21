package com.airi.core.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class MultimodalRoutingPolicyTest {

    @Test
    fun `routes text and images only to matching capabilities`() {
        assertEquals(
            RequestModality.TEXT_ONLY,
            assertIs<MultimodalRoutingResult.Route>(
                MultimodalRoutingPolicy.decide(true, 0, ModelInputCapabilities(acceptsText = true, acceptsImages = false))
            ).modality
        )
        assertEquals(
            RequestModality.TEXT_AND_IMAGE,
            assertIs<MultimodalRoutingResult.Route>(
                MultimodalRoutingPolicy.decide(true, 2, ModelInputCapabilities(acceptsText = true, acceptsImages = true))
            ).modality
        )
    }

    @Test
    fun `rejects image request without a vision capable model`() {
        assertIs<MultimodalRoutingResult.Rejected>(
            MultimodalRoutingPolicy.decide(false, 1, ModelInputCapabilities(acceptsText = true, acceptsImages = false))
        )
    }

    @Test
    fun `rejects empty request and negative image count`() {
        assertIs<MultimodalRoutingResult.Rejected>(
            MultimodalRoutingPolicy.decide(false, 0, ModelInputCapabilities(acceptsText = true, acceptsImages = true))
        )
        runCatching {
            MultimodalRoutingPolicy.decide(true, -1, ModelInputCapabilities(acceptsText = true, acceptsImages = true))
        }.onSuccess { error("Negative image count must be rejected.") }
    }
}
