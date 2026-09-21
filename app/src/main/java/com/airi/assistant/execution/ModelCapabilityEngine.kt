package com.airi.assistant.execution

import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelInfo
import com.airi.assistant.execution.CloudProvider

/** Single source of truth for model/input compatibility decisions. */
enum class Capability {
    TEXT_INPUT, IMAGE_INPUT, IMAGE_UNDERSTANDING, AUDIO_INPUT, AUDIO_UNDERSTANDING,
    VIDEO_INPUT, VIDEO_UNDERSTANDING, DOCUMENT_INPUT, PDF_INPUT, OCR,
    TOOL_CALLING, FUNCTION_CALLING, STRUCTURED_OUTPUT, JSON_OUTPUT, STREAMING,
    VISION, LONG_CONTEXT
}

enum class CapabilityStatus { SUPPORTED, UNSUPPORTED, SUPPORTED_WITH_LIMITS, UNKNOWN, TEMPORARILY_UNAVAILABLE }
enum class CapabilityConfidence { VERIFIED, DECLARED, INFERRED, UNKNOWN }
enum class AttachmentRequirement { TEXT, IMAGE, VIDEO, AUDIO, DOCUMENT, PDF }
enum class CompatibilityDecision { ALLOW, ALLOW_WITH_WARNING, BLOCK, ROUTE_TO_COMPATIBLE_MODEL }
enum class ModelAvailability { AVAILABLE, UNAVAILABLE, UNKNOWN }
enum class ModelFeasibility { FEASIBLE, INSUFFICIENT_RESOURCES, UNKNOWN }

data class ModelReadiness(
    val availability: ModelAvailability = ModelAvailability.UNKNOWN,
    val feasibility: ModelFeasibility = ModelFeasibility.UNKNOWN,
    val reason: String = ""
)

data class CapabilityLimit(
    val maxImages: Int? = null,
    val maxFileSizeBytes: Long? = null,
    val maxInputBytes: Long? = null,
    val supportedMimeTypes: Set<String> = emptySet()
)

data class ModelCapabilityDescriptor(
    val modelId: String,
    val displayName: String,
    val providerId: String,
    val runtimeId: String,
    val declared: Map<Capability, CapabilityStatus>,
    val runtime: Map<Capability, CapabilityStatus>,
    val confidence: CapabilityConfidence,
    val limits: CapabilityLimit = CapabilityLimit(),
    val explanation: String = "",
    val readiness: ModelReadiness = ModelReadiness()
) {
    fun status(capability: Capability): CapabilityStatus =
        runtime[capability] ?: declared[capability] ?: CapabilityStatus.UNKNOWN

    fun isReady(capability: Capability): Boolean = status(capability) == CapabilityStatus.SUPPORTED ||
        status(capability) == CapabilityStatus.SUPPORTED_WITH_LIMITS
}

data class AttachmentCompatibility(
    val decision: CompatibilityDecision,
    val status: CapabilityStatus,
    val capability: Capability,
    val reason: String,
    val descriptor: ModelCapabilityDescriptor
)

object ModelCapabilityEngine {
    fun fromLocal(
        model: ModelInfo,
        capabilities: ModelCapabilities,
        mmprojLoaded: Boolean,
        availableRamMb: Int? = null,
        modelAvailable: Boolean = true,
    ): ModelCapabilityDescriptor {
        val declaredVision = ModelCapabilities.declaresVision(model)
        val runtimeVision = when {
            !declaredVision -> CapabilityStatus.UNSUPPORTED
            mmprojLoaded && capabilities.vision -> CapabilityStatus.SUPPORTED
            else -> CapabilityStatus.TEMPORARILY_UNAVAILABLE
        }
        val base = mutableMapOf(
            Capability.TEXT_INPUT to if (capabilities.text) CapabilityStatus.SUPPORTED else CapabilityStatus.UNKNOWN,
            Capability.IMAGE_INPUT to if (declaredVision) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            Capability.IMAGE_UNDERSTANDING to if (declaredVision) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            Capability.VISION to if (declaredVision) CapabilityStatus.SUPPORTED else CapabilityStatus.UNSUPPORTED,
            Capability.AUDIO_INPUT to CapabilityStatus.UNSUPPORTED,
            Capability.VIDEO_INPUT to CapabilityStatus.UNSUPPORTED,
            Capability.DOCUMENT_INPUT to CapabilityStatus.SUPPORTED,
            Capability.PDF_INPUT to CapabilityStatus.UNKNOWN,
            Capability.OCR to if (declaredVision) CapabilityStatus.UNKNOWN else CapabilityStatus.UNSUPPORTED,
            Capability.TOOL_CALLING to if (capabilities.toolCalling) CapabilityStatus.SUPPORTED else CapabilityStatus.UNKNOWN,
            Capability.STREAMING to CapabilityStatus.SUPPORTED,
            Capability.STRUCTURED_OUTPUT to CapabilityStatus.SUPPORTED,
            Capability.JSON_OUTPUT to CapabilityStatus.SUPPORTED,
            Capability.LONG_CONTEXT to if (model.contextSize >= 8192) CapabilityStatus.SUPPORTED else CapabilityStatus.SUPPORTED_WITH_LIMITS
        )
        val runtime = base.toMutableMap().apply {
            put(Capability.IMAGE_INPUT, runtimeVision)
            put(Capability.IMAGE_UNDERSTANDING, runtimeVision)
            put(Capability.VISION, runtimeVision)
        }
        val feasibility = when {
            model.ramRequiredMb <= 0 || availableRamMb == null -> ModelFeasibility.UNKNOWN
            availableRamMb >= model.ramRequiredMb -> ModelFeasibility.FEASIBLE
            else -> ModelFeasibility.INSUFFICIENT_RESOURCES
        }
        val readinessReason = when {
            !modelAvailable -> "النموذج غير متاح في مسار التخزين الحالي."
            feasibility == ModelFeasibility.INSUFFICIENT_RESOURCES -> "الذاكرة المتاحة أقل من متطلبات النموذج المعروفة."
            else -> ""
        }
        return ModelCapabilityDescriptor(
            modelId = model.id,
            displayName = model.name,
            providerId = "local",
            runtimeId = "llama.cpp",
            declared = base,
            runtime = runtime,
            confidence = if (declaredVision) CapabilityConfidence.DECLARED else CapabilityConfidence.VERIFIED,
            limits = CapabilityLimit(maxImages = if (runtimeVision == CapabilityStatus.SUPPORTED) 1 else null, maxFileSizeBytes = 12L * 1024L * 1024L),
            explanation = if (declaredVision && runtimeVision != CapabilityStatus.SUPPORTED) "يتطلب هذا النموذج multimodal projector صالحًا ومحمّلًا." else "",
            readiness = ModelReadiness(
                availability = if (modelAvailable) ModelAvailability.AVAILABLE else ModelAvailability.UNAVAILABLE,
                feasibility = feasibility,
                reason = readinessReason,
            )
        )
    }

    fun fromCloud(provider: CloudProvider, modelId: String): ModelCapabilityDescriptor {
        val exact = modelId.trim().lowercase()
        val vision = when (provider) {
            CloudProvider.GEMINI -> exact.startsWith("gemini-2.5") || exact.startsWith("gemini-2.0") || exact.startsWith("gemini-3")
            CloudProvider.OPENAI -> exact.startsWith("gpt-4o") || exact.startsWith("gpt-4.1") || exact.startsWith("gpt-4.5") || exact.startsWith("o1") || exact.startsWith("o3") || exact.startsWith("o4")
            CloudProvider.OPENROUTER -> exact.contains("gemini") || exact.contains("gpt-4o") || exact.contains("claude-3") || exact.contains("qwen-vl") || exact.contains("llava") || exact.contains("vision")
            CloudProvider.CUSTOM -> false
            else -> false
        }
        val status = if (vision) CapabilityStatus.SUPPORTED else if (provider == CloudProvider.CUSTOM) CapabilityStatus.UNKNOWN else CapabilityStatus.UNSUPPORTED
        val values = Capability.entries.associateWith { CapabilityStatus.UNKNOWN }.toMutableMap()
        values[Capability.TEXT_INPUT] = CapabilityStatus.SUPPORTED
        values[Capability.IMAGE_INPUT] = status
        values[Capability.IMAGE_UNDERSTANDING] = status
        values[Capability.VISION] = status
        values[Capability.AUDIO_INPUT] = CapabilityStatus.UNKNOWN
        values[Capability.VIDEO_INPUT] = CapabilityStatus.UNKNOWN
        values[Capability.DOCUMENT_INPUT] = CapabilityStatus.SUPPORTED_WITH_LIMITS
        values[Capability.STREAMING] = CapabilityStatus.SUPPORTED
        values[Capability.TOOL_CALLING] = CapabilityStatus.UNKNOWN
        values[Capability.STRUCTURED_OUTPUT] = CapabilityStatus.UNKNOWN
        return ModelCapabilityDescriptor(
            modelId = exact,
            displayName = modelId,
            providerId = provider.name.lowercase(),
            runtimeId = provider.name.lowercase(),
            declared = values,
            runtime = values,
            confidence = if (provider == CloudProvider.CUSTOM) CapabilityConfidence.UNKNOWN else CapabilityConfidence.VERIFIED,
            limits = CapabilityLimit(maxImages = if (vision) 4 else null, maxFileSizeBytes = 12L * 1024L * 1024L),
            explanation = if (vision) "تم التعرف على capability حسب model ID المحدد." else "هذا model ID لا يعلن دعم الصور في كتالوج AIRI الحالي.",
            readiness = ModelReadiness(
                availability = if (exact.isBlank()) ModelAvailability.UNKNOWN else ModelAvailability.AVAILABLE,
                feasibility = ModelFeasibility.UNKNOWN,
            )
        )
    }

    fun check(descriptor: ModelCapabilityDescriptor, requirement: AttachmentRequirement, mimeType: String?, sizeBytes: Long?, count: Int = 1): AttachmentCompatibility {
        val capability = when (requirement) {
            AttachmentRequirement.TEXT -> Capability.TEXT_INPUT
            AttachmentRequirement.IMAGE -> Capability.IMAGE_UNDERSTANDING
            AttachmentRequirement.VIDEO -> Capability.VIDEO_UNDERSTANDING
            AttachmentRequirement.AUDIO -> Capability.AUDIO_UNDERSTANDING
            AttachmentRequirement.DOCUMENT -> Capability.DOCUMENT_INPUT
            AttachmentRequirement.PDF -> Capability.PDF_INPUT
        }
        val status = descriptor.status(capability)
        val limit = descriptor.limits
        val tooLarge = limit.maxFileSizeBytes?.let { sizeBytes != null && sizeBytes > it } == true
        val tooMany = limit.maxImages?.let { count > it } == true
        val unsupportedMime = limit.supportedMimeTypes.isNotEmpty() && mimeType != null && mimeType !in limit.supportedMimeTypes
        val reason = when {
            descriptor.readiness.availability == ModelAvailability.UNAVAILABLE -> descriptor.readiness.reason.ifBlank { "النموذج غير متاح حاليًا." }
            descriptor.readiness.feasibility == ModelFeasibility.INSUFFICIENT_RESOURCES -> descriptor.readiness.reason.ifBlank { "موارد الجهاز غير كافية لتشغيل النموذج بأمان." }
            tooLarge -> "حجم المرفق يتجاوز الحد المعروف للنموذج."
            tooMany -> "عدد الصور يتجاوز الحد المعروف للنموذج."
            unsupportedMime -> "نوع MIME غير مدعوم لهذا النموذج."
            status == CapabilityStatus.TEMPORARILY_UNAVAILABLE -> descriptor.explanation.ifBlank { "القدرة معلنة لكن runtime غير جاهز حاليًا." }
            status == CapabilityStatus.UNKNOWN -> "لم يتمكن AIRI من التحقق من دعم هذه capability لهذا النموذج."
            status == CapabilityStatus.UNSUPPORTED -> "النموذج الحالي لا يدعم هذا النوع من المدخلات."
            else -> ""
        }
        val decision = when {
            descriptor.readiness.availability == ModelAvailability.UNAVAILABLE ||
                descriptor.readiness.feasibility == ModelFeasibility.INSUFFICIENT_RESOURCES ||
                tooLarge || tooMany || unsupportedMime || status == CapabilityStatus.UNSUPPORTED || status == CapabilityStatus.TEMPORARILY_UNAVAILABLE -> CompatibilityDecision.BLOCK
            status == CapabilityStatus.UNKNOWN -> CompatibilityDecision.ALLOW_WITH_WARNING
            status == CapabilityStatus.SUPPORTED_WITH_LIMITS -> CompatibilityDecision.ALLOW_WITH_WARNING
            else -> CompatibilityDecision.ALLOW
        }
        return AttachmentCompatibility(decision, status, capability, reason, descriptor)
    }
}
