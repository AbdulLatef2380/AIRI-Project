package com.airi.assistant.product

import com.airi.assistant.execution.Capability
import com.airi.assistant.execution.CapabilityConfidence
import com.airi.assistant.execution.CapabilityStatus
import com.airi.assistant.execution.ModelCapabilityDescriptor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiriIdentityProfileTest {
    private fun descriptor(vision: CapabilityStatus, provider: String = "local") =
        ModelCapabilityDescriptor(
            modelId = "current-model",
            displayName = "Current AIRI Model",
            providerId = provider,
            runtimeId = provider,
            declared = mapOf(Capability.IMAGE_UNDERSTANDING to vision),
            runtime = mapOf(Capability.IMAGE_UNDERSTANDING to vision),
            confidence = CapabilityConfidence.VERIFIED,
        )

    @Test
    fun recognizesArabicAndEnglishIdentityQuestions() {
        assertTrue(AiriIdentityProfile.isIdentityQuestion("من أنت؟"))
        assertTrue(AiriIdentityProfile.isIdentityQuestion("What can you do?"))
        assertFalse(AiriIdentityProfile.isIdentityQuestion("اكتب لي رسالة قصيرة"))
    }

    @Test
    fun identityPromptStatesOriginAndDoesNotInventBiography() {
        val prompt = AiriIdentityProfile.promptContext("من طورك؟", descriptor(CapabilityStatus.UNSUPPORTED))
        assertTrue(prompt.contains("Abdul Latif"))
        assertTrue(prompt.contains("لا تخترع"))
        assertTrue(prompt.contains("لا يدعم فهم الصور"))
    }

    @Test
    fun unavailableVisionIsDescribedAsNotReady() {
        val prompt = AiriIdentityProfile.promptContext(
            "ماذا تستطيع مع الصور؟",
            descriptor(CapabilityStatus.TEMPORARILY_UNAVAILABLE),
        )
        assertTrue(prompt.contains("غير جاهز"))
        assertTrue(prompt.contains("runtime"))
    }

    @Test
    fun nonIdentityInputDoesNotReceiveIdentityInjection() {
        assertTrue(AiriIdentityProfile.promptContext("حلل هذا النص", null).isEmpty())
    }
}
