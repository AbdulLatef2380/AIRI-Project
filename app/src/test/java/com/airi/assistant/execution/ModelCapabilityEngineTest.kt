package com.airi.assistant.execution

import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.ai.ModelSource
import org.junit.Assert.assertEquals
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
}
