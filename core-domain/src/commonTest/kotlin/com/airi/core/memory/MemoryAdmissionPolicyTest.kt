package com.airi.core.memory

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MemoryAdmissionPolicyTest {

    @Test
    fun sensitiveTextIsNeverEligibleForEmbedding() {
        val decision = MemoryAdmissionPolicy.decide(
            role = "user",
            content = "My API key is private-value and should stay private."
        )

        assertFalse(decision.shouldEmbed)
        assertFalse(decision.shouldExtractFacts)
        assertTrue(MemoryAdmissionPolicy.containsSensitiveData("api key: secret"))
    }

    @Test
    fun shortGreetingIsExcludedFromSemanticMemory() {
        val decision = MemoryAdmissionPolicy.decide("user", "مرحبا")

        assertFalse(decision.shouldEmbed)
        assertFalse(decision.shouldExtractFacts)
    }

    @Test
    fun explicitArabicMemoryRequestIsEligibleWhenNonSensitive() {
        val decision = MemoryAdmissionPolicy.decide(
            role = "user",
            content = "تذكر أنني أفضل الإجابات المختصرة باللغة العربية في هذا المشروع."
        )

        assertTrue(decision.shouldEmbed)
        assertTrue(decision.shouldExtractFacts)
    }

    @Test
    fun negatedOrQuotedMemoryRequestDoesNotExtractFacts() {
        val negated = MemoryAdmissionPolicy.decide(
            "user",
            "Please don't remember this: I prefer short answers in Arabic every time."
        )
        val quoted = MemoryAdmissionPolicy.decide(
            "user",
            "The text says \"remember this\", but I am only discussing the wording in this message."
        )
        val negatedArabic = MemoryAdmissionPolicy.decide(
            "user",
            "لا تحفظ أنني أفضل الإجابات المختصرة باللغة العربية في هذا المشروع."
        )

        assertFalse(negated.shouldExtractFacts)
        assertFalse(quoted.shouldExtractFacts)
        assertFalse(negatedArabic.shouldExtractFacts)
    }

    @Test
    fun onlySafeDurableMemoryCategoriesAreAccepted() {
        assertTrue(MemoryAdmissionPolicy.allowExtractedFact("preference=dark theme"))
        assertTrue(MemoryAdmissionPolicy.allowExtractedFact("language=Arabic"))
        assertFalse(MemoryAdmissionPolicy.allowExtractedFact("email=user@example.com"))
        assertFalse(MemoryAdmissionPolicy.allowExtractedFact("identity=private user name"))
    }
}
