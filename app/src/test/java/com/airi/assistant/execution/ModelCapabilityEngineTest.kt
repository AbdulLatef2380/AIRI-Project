package com.airi.assistant.execution

import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelCapabilityEngineTest {
    private fun local(name: String) = ModelInfo(name, "$name.gguf", 1L, "Q4", "/models/$name.gguf", ModelSource.LOCAL_FILE)
    private val textCaps = ModelCapabilities(true, false, false, true, "llama")
    private val visionCaps = ModelCapabilities(true, true, false, true, "llava")

    @Test fun textOnlyBlocksImageAndAllowsText() {
        val d = ModelCapabilityEngine.fromLocal(local("llama-3.1-8b"), textCaps, mmprojLoaded = false)
        assertEquals(CapabilityStatus.UNSUPPORTED, d.status(Capability.IMAGE_UNDERSTANDING))
        assertEquals(CompatibilityDecision.BLOCK, ModelCapabilityEngine.check(d, AttachmentRequirement.IMAGE, "image/jpeg", 100).decision)
        assertEquals(CompatibilityDecision.ALLOW, ModelCapabilityEngine.check(d, AttachmentRequirement.TEXT, "text/plain", 100).decision)
    }

    @Test fun declaredVisionWithoutProjectorIsUnavailable() {
        val d = ModelCapabilityEngine.fromLocal(local("llava-1.6"), visionCaps, mmprojLoaded = false)
        assertEquals(CapabilityStatus.TEMPORARILY_UNAVAILABLE, d.status(Capability.IMAGE_UNDERSTANDING))
        assertEquals(CompatibilityDecision.BLOCK, ModelCapabilityEngine.check(d, AttachmentRequirement.IMAGE, "image/jpeg", 100).decision)
    }

    @Test fun visionWithProjectorAllowsImage() {
        val d = ModelCapabilityEngine.fromLocal(local("llava-1.6"), visionCaps, mmprojLoaded = true)
        assertEquals(CompatibilityDecision.ALLOW, ModelCapabilityEngine.check(d, AttachmentRequirement.IMAGE, "image/jpeg", 100).decision)
    }

    @Test fun cloudUsesExactModelIdAndCustomIsUnknown() {
        val gemini = ModelCapabilityEngine.fromCloud(CloudProvider.GEMINI, "gemini-2.5-flash")
        assertEquals(CapabilityStatus.SUPPORTED, gemini.status(Capability.IMAGE_UNDERSTANDING))
        val custom = ModelCapabilityEngine.fromCloud(CloudProvider.CUSTOM, "my-model")
        assertEquals(CapabilityStatus.UNKNOWN, custom.status(Capability.IMAGE_UNDERSTANDING))
        assertTrue(custom.confidence == CapabilityConfidence.UNKNOWN)
        assertEquals(
            CompatibilityDecision.BLOCK,
            ModelCapabilityEngine.check(custom, AttachmentRequirement.IMAGE, "image/jpeg", 100).decision,
        )
    }

    @Test fun cloudAttachmentMatrixRequiresExactReviewedModelAndNativeMime() {
        val openAi = ModelCapabilityEngine.fromCloud(CloudProvider.OPENAI, "gpt-4o-mini")
        val unknownOpenAi = ModelCapabilityEngine.fromCloud(CloudProvider.OPENAI, "gpt-4.1-experimental")
        val openRouter = ModelCapabilityEngine.fromCloud(CloudProvider.OPENROUTER, "qwen/qwen3.8-27b:free")
        val kimi = ModelCapabilityEngine.fromCloud(CloudProvider.KIMI, "moonshot-v1-8k")

        assertTrue(ModelCapabilityEngine.hasNativeAttachmentTransport(openAi, AttachmentRequirement.IMAGE, "image/png"))
        assertTrue(ModelCapabilityEngine.hasNativeAttachmentTransport(openAi, AttachmentRequirement.PDF, "application/pdf"))
        assertFalse(ModelCapabilityEngine.hasNativeAttachmentTransport(openAi, AttachmentRequirement.IMAGE, "image/heic"))
        assertEquals(CapabilityStatus.UNKNOWN, unknownOpenAi.status(Capability.IMAGE_UNDERSTANDING))
        assertEquals(CompatibilityDecision.BLOCK, ModelCapabilityEngine.check(
            unknownOpenAi, AttachmentRequirement.IMAGE, "image/jpeg", 100,
        ).decision)
        assertEquals(CapabilityStatus.UNKNOWN, openRouter.status(Capability.IMAGE_UNDERSTANDING))
        assertEquals(CapabilityStatus.UNSUPPORTED, kimi.status(Capability.IMAGE_UNDERSTANDING))
    }

    @Test fun geminiAdvertisesOnlyImplementedPdfAndImageRoutesButNotVideoOrGenericDocuments() {
        val local = ModelCapabilityEngine.fromLocal(local("llama-3.1-8b"), textCaps, mmprojLoaded = false)
        val cloud = ModelCapabilityEngine.fromCloud(CloudProvider.GEMINI, "gemini-2.5-flash")
        listOf(local).forEach { descriptor ->
            assertEquals(CapabilityStatus.UNSUPPORTED, descriptor.status(Capability.DOCUMENT_INPUT))
            assertEquals(CapabilityStatus.UNSUPPORTED, descriptor.status(Capability.PDF_INPUT))
            assertEquals(CapabilityStatus.UNSUPPORTED, descriptor.status(Capability.VIDEO_INPUT))
            assertEquals(CapabilityStatus.UNSUPPORTED, descriptor.status(Capability.VIDEO_UNDERSTANDING))
        }
        assertEquals(CapabilityStatus.UNSUPPORTED, cloud.status(Capability.DOCUMENT_INPUT))
        assertEquals(CapabilityStatus.SUPPORTED_WITH_LIMITS, cloud.status(Capability.PDF_INPUT))
        assertEquals(CapabilityStatus.UNSUPPORTED, cloud.status(Capability.VIDEO_UNDERSTANDING))
    }

    @Test fun capabilityIsNotFeasibility() {
        val model = local("llama-7b").copy(ramRequiredMb = 4096)
        val descriptor = ModelCapabilityEngine.fromLocal(
            model,
            textCaps,
            mmprojLoaded = false,
            availableRamMb = 1800,
        )
        assertEquals(CapabilityStatus.SUPPORTED, descriptor.status(Capability.TEXT_INPUT))
        assertEquals(ModelAvailability.AVAILABLE, descriptor.readiness.availability)
        assertEquals(ModelFeasibility.INSUFFICIENT_RESOURCES, descriptor.readiness.feasibility)
        assertEquals(
            CompatibilityDecision.BLOCK,
            ModelCapabilityEngine.check(descriptor, AttachmentRequirement.TEXT, "text/plain", 10).decision,
        )
    }

    @Test fun unavailableModelBlocksBeforeAttachmentCapability() {
        val descriptor = ModelCapabilityEngine.fromLocal(
            local("llama-3.1-8b"),
            textCaps,
            mmprojLoaded = false,
            modelAvailable = false,
        )
        assertEquals(ModelAvailability.UNAVAILABLE, descriptor.readiness.availability)
        assertEquals(
            CompatibilityDecision.BLOCK,
            ModelCapabilityEngine.check(descriptor, AttachmentRequirement.TEXT, "text/plain", 10).decision,
        )
    }

    @Test fun sufficientResourcesAreFeasible() {
        val descriptor = ModelCapabilityEngine.fromLocal(
            local("llama-3.1-8b").copy(ramRequiredMb = 2048),
            textCaps,
            mmprojLoaded = false,
            availableRamMb = 4096,
        )
        assertEquals(ModelFeasibility.FEASIBLE, descriptor.readiness.feasibility)
        assertEquals(
            CompatibilityDecision.ALLOW,
            ModelCapabilityEngine.check(descriptor, AttachmentRequirement.TEXT, "text/plain", 10).decision,
        )
    }

    @Test fun unknownResourcesRemainUnknownInsteadOfClaimingFeasibility() {
        val descriptor = ModelCapabilityEngine.fromLocal(
            local("llama-3.1-8b").copy(ramRequiredMb = 2048),
            textCaps,
            mmprojLoaded = false,
            availableRamMb = null,
        )
        assertEquals(ModelFeasibility.UNKNOWN, descriptor.readiness.feasibility)
    }

    @Test fun openAiAndAnthropicAdvertiseOnlyImplementedNativeDocumentRoutes() {
        val openAi = ModelCapabilityEngine.fromCloud(CloudProvider.OPENAI, "gpt-4.1")
        assertEquals(CapabilityStatus.UNSUPPORTED, openAi.status(Capability.DOCUMENT_INPUT))
        assertEquals(CapabilityStatus.SUPPORTED_WITH_LIMITS, openAi.status(Capability.PDF_INPUT))
        assertEquals(CapabilityStatus.UNSUPPORTED, openAi.status(Capability.VIDEO_UNDERSTANDING))
        assertEquals(
            CompatibilityDecision.ALLOW_WITH_WARNING,
            ModelCapabilityEngine.check(openAi, AttachmentRequirement.PDF, "application/pdf", 100).decision,
        )

        val anthropic = ModelCapabilityEngine.fromCloud(CloudProvider.ANTHROPIC, "claude-sonnet-4-5")
        assertEquals(CapabilityStatus.UNSUPPORTED, anthropic.status(Capability.DOCUMENT_INPUT))
        assertEquals(CapabilityStatus.SUPPORTED_WITH_LIMITS, anthropic.status(Capability.PDF_INPUT))
        assertEquals(CapabilityStatus.SUPPORTED, anthropic.status(Capability.IMAGE_UNDERSTANDING))
    }
}
