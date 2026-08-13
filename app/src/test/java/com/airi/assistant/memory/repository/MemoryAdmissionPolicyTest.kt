package com.airi.assistant.memory.repository

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryAdmissionPolicyTest {

    @Test
    fun `sensitive text is never eligible for embedding`() {
        val decision = MemoryAdmissionPolicy.decide(
            role = "user",
            content = "My API key is sk-example-secret-value and should stay private."
        )

        assertFalse(decision.shouldEmbed)
        assertFalse(decision.shouldExtractFacts)
        assertTrue(MemoryAdmissionPolicy.containsSensitiveData("api key: secret"))
    }

    @Test
    fun `short greeting is excluded from semantic memory`() {
        val decision = MemoryAdmissionPolicy.decide("user", "مرحبا")

        assertFalse(decision.shouldEmbed)
        assertFalse(decision.shouldExtractFacts)
    }

    @Test
    fun `explicit Arabic memory request is eligible when non-sensitive`() {
        val decision = MemoryAdmissionPolicy.decide(
            role = "user",
            content = "تذكر أنني أفضل الإجابات المختصرة باللغة العربية في هذا المشروع."
        )

        assertTrue(decision.shouldEmbed)
        assertTrue(decision.shouldExtractFacts)
    }

    @Test
    fun `only safe durable memory categories are accepted`() {
        assertTrue(MemoryAdmissionPolicy.allowExtractedFact("preference=dark theme"))
        assertTrue(MemoryAdmissionPolicy.allowExtractedFact("language=Arabic"))
        assertFalse(MemoryAdmissionPolicy.allowExtractedFact("email=user@example.com"))
        assertFalse(MemoryAdmissionPolicy.allowExtractedFact("identity=private user name"))
    }
}
