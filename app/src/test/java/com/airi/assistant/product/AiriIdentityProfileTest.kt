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

    @Test
    fun generativeUiContractIsDetailedForCloudAndOmittedForLocal() {
        val local = AiriIdentityProfile.runtimeContext(descriptor(CapabilityStatus.SUPPORTED, "local"))
        val cloud = AiriIdentityProfile.runtimeContext(descriptor(CapabilityStatus.SUPPORTED, "openai"))

        assertFalse(local.contains("Allowed block types and exact fields"))
        assertTrue(cloud.contains("Allowed block types and exact fields"))
        assertTrue(cloud.contains("supportingText"))
        assertTrue(cloud.contains("submitLabel"))
        assertTrue(cloud.contains("unique short ids"))
    }

    @Test
    fun contractStatesLimitsAndIsGatedByRequestIntent() {
        val cloud = descriptor(CapabilityStatus.SUPPORTED, "openai")
        val unknownRequest = AiriIdentityProfile.runtimeContext(cloud)
        assertTrue(unknownRequest.contains("Hard limits"))
        assertTrue(unknownRequest.contains("every id in the document must be unique"))

        val chatty = AiriIdentityProfile.runtimeContext(cloud, "صباح الخير، كيف حالك؟")
        assertFalse(chatty.contains("airi-ui"))

        val comparison = AiriIdentityProfile.runtimeContext(cloud, "قارن بين الخيارين في جدول")
        assertTrue(comparison.contains("airi-ui"))
        val english = AiriIdentityProfile.runtimeContext(cloud, "Give me a checklist for moving")
        assertTrue(english.contains("airi-ui"))

        val local = AiriIdentityProfile.runtimeContext(descriptor(CapabilityStatus.SUPPORTED, "local"), "قارن بين الخيارين")
        assertFalse(local.contains("airi-ui"))
    }

    @Test
    fun intentCheckIgnoresWordsThatOnlyContainAKeyword() {
        assertFalse(GenerativeUiIntent.suggestsStructuredOutput("this is a tablet and information"))
        assertTrue(GenerativeUiIntent.suggestsStructuredOutput("show a bar chart of sales"))
        assertFalse(GenerativeUiIntent.suggestsStructuredOutput("   "))
    }
}
