package com.airi.assistant.execution

import com.airi.assistant.ai.ModelCapabilities
import com.airi.assistant.ai.ModelInfo

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

/**
 * Attachment capability is deliberately narrower than text-model discovery.
 * A cloud model name is not proof of a modality. Only model IDs included in
 * the local, reviewed allowlist can use native image/PDF transports; unknown
 * IDs and unverified OpenAI-compatible endpoints fail closed.
 */
object ModelCapabilityEngine {
    private data class CloudAttachmentProfile(
        val vision: Boolean,
        val pdf: Boolean,
        val maxImages: Int = 4,
        val maxNativeFileBytes: Long = 20L * 1024L * 1024L,
    )

    private val geminiAttachmentModels = setOf(
        "gemini-flash-latest",
        "gemini-2.5-flash",
        "gemini-2.5-pro",
        "gemini-3.5-flash-lite",
    )
    private val openAiAttachmentModels = setOf(
        "gpt-4o",
        "gpt-4o-mini",
        "gpt-4.1",
        "gpt-4.1-mini",
        "gpt-4.1-nano",
        "gpt-4.5-preview",
        "gpt-5",
        "gpt-5-mini",
        "gpt-5-nano",
        "o1",
        "o3",
        "o3-mini",
        "o4-mini",
    )
    private val anthropicAttachmentModels = setOf(
        "claude-haiku-4-5",
        "claude-sonnet-4-5",
        "claude-opus-4-1",
        "claude-sonnet-4-0",
        "claude-opus-4-0",
        "claude-3-7-sonnet-latest",
        "claude-3-5-sonnet-latest",
    )
    private val nativeImageMimeTypes = setOf("image/jpeg", "image/png", "image/gif", "image/webp")

    private fun cloudAttachmentProfile(provider: CloudProvider, modelId: String): CloudAttachmentProfile? {
        val exact = modelId.trim().lowercase()
        if (exact.isBlank()) return null
        return when {
            provider == CloudProvider.GEMINI && exact in geminiAttachmentModels ->
                CloudAttachmentProfile(vision = true, pdf = true)
            provider == CloudProvider.OPENAI && exact in openAiAttachmentModels ->
                CloudAttachmentProfile(vision = true, pdf = true)
            provider == CloudProvider.ANTHROPIC && exact in anthropicAttachmentModels ->
                CloudAttachmentProfile(vision = true, pdf = true)
            else -> null
        }
    }

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
            Capability.AUDIO_UNDERSTANDING to CapabilityStatus.UNSUPPORTED,
            Capability.VIDEO_INPUT to CapabilityStatus.UNSUPPORTED,
            Capability.VIDEO_UNDERSTANDING to CapabilityStatus.UNSUPPORTED,
            Capability.DOCUMENT_INPUT to CapabilityStatus.UNSUPPORTED,
            Capability.PDF_INPUT to CapabilityStatus.UNSUPPORTED,
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
        val profile = cloudAttachmentProfile(provider, exact)
        val modelNamed = exact.isNotBlank()
        val firstPartyModalityMayVary = provider in setOf(
            CloudProvider.GEMINI, CloudProvider.OPENAI, CloudProvider.ANTHROPIC
        )
        val visionFallback = if (
            (firstPartyModalityMayVary && modelNamed) ||
            provider == CloudProvider.OPENROUTER || provider == CloudProvider.CUSTOM
        ) {
            CapabilityStatus.UNKNOWN
        } else {
            CapabilityStatus.UNSUPPORTED
        }
        val pdfFallback = if (firstPartyModalityMayVary && modelNamed) {
            CapabilityStatus.UNKNOWN
        } else {
            CapabilityStatus.UNSUPPORTED
        }
        val visionStatus = if (profile?.vision == true) CapabilityStatus.SUPPORTED else visionFallback
        val pdfStatus = if (profile?.pdf == true) CapabilityStatus.SUPPORTED_WITH_LIMITS else pdfFallback
        val values = Capability.entries.associateWith { CapabilityStatus.UNKNOWN }.toMutableMap()
        values[Capability.TEXT_INPUT] = if (modelNamed) CapabilityStatus.SUPPORTED else CapabilityStatus.UNKNOWN
        values[Capability.IMAGE_INPUT] = visionStatus
        values[Capability.IMAGE_UNDERSTANDING] = visionStatus
        values[Capability.VISION] = visionStatus
        values[Capability.AUDIO_INPUT] = CapabilityStatus.UNSUPPORTED
        values[Capability.AUDIO_UNDERSTANDING] = CapabilityStatus.UNSUPPORTED
        values[Capability.VIDEO_INPUT] = CapabilityStatus.UNSUPPORTED
        values[Capability.VIDEO_UNDERSTANDING] = CapabilityStatus.UNSUPPORTED
        values[Capability.DOCUMENT_INPUT] = CapabilityStatus.UNSUPPORTED
        values[Capability.PDF_INPUT] = pdfStatus
        values[Capability.OCR] = CapabilityStatus.UNSUPPORTED
        values[Capability.STREAMING] = if (modelNamed) CapabilityStatus.SUPPORTED else CapabilityStatus.UNKNOWN
        values[Capability.TOOL_CALLING] = CapabilityStatus.UNKNOWN
        values[Capability.FUNCTION_CALLING] = CapabilityStatus.UNKNOWN
        values[Capability.STRUCTURED_OUTPUT] = CapabilityStatus.UNKNOWN
        values[Capability.JSON_OUTPUT] = CapabilityStatus.UNKNOWN
        return ModelCapabilityDescriptor(
            modelId = exact,
            displayName = modelId,
            providerId = provider.name.lowercase(),
            runtimeId = provider.name.lowercase(),
            declared = values,
            runtime = values,
            confidence = if (profile != null) CapabilityConfidence.DECLARED else CapabilityConfidence.UNKNOWN,
            limits = CapabilityLimit(
                maxImages = profile?.takeIf { it.vision }?.maxImages,
                maxFileSizeBytes = profile?.maxNativeFileBytes ?: 12L * 1024L * 1024L,
            ),
            explanation = when {
                profile != null -> "النقل الأصلي محدد لمعرّف نموذج معروف، والعقد يبقى مشروطًا بقبول المزوّد الفعلي."
                modelNamed -> "لا توجد مصفوفة نقل مرفقات موثقة لهذا الموديل؛ رُفضت القدرات متعددة الوسائط افتراضيًا."
                else -> "معرّف النموذج غير محدد؛ لا يمكن التحقق من توافق المرفقات."
            },
            readiness = ModelReadiness(
                availability = if (modelNamed) ModelAvailability.AVAILABLE else ModelAvailability.UNKNOWN,
                feasibility = ModelFeasibility.UNKNOWN,
            )
        )
    }

    /** True only where both a reviewed model profile and a compatible adapter payload exist. */
    fun hasNativeAttachmentTransport(
        descriptor: ModelCapabilityDescriptor,
        requirement: AttachmentRequirement,
        mimeType: String?,
    ): Boolean {
        val provider = CloudProvider.entries.firstOrNull { it.name.equals(descriptor.providerId, ignoreCase = true) }
            ?: return false
        val profile = cloudAttachmentProfile(provider, descriptor.modelId) ?: return false
        if (!descriptor.isReady(when (requirement) {
                AttachmentRequirement.IMAGE -> Capability.IMAGE_UNDERSTANDING
                AttachmentRequirement.PDF -> Capability.PDF_INPUT
                AttachmentRequirement.TEXT -> Capability.TEXT_INPUT
                AttachmentRequirement.DOCUMENT -> Capability.DOCUMENT_INPUT
                AttachmentRequirement.VIDEO -> Capability.VIDEO_UNDERSTANDING
                AttachmentRequirement.AUDIO -> Capability.AUDIO_UNDERSTANDING
            })) return false
        val mime = mimeType.orEmpty().trim().lowercase().substringBefore(';')
        return when (requirement) {
            AttachmentRequirement.IMAGE -> profile.vision &&
                (mime.isBlank() || mime == "application/octet-stream" || mime in nativeImageMimeTypes)
            AttachmentRequirement.PDF -> profile.pdf && mime == "application/pdf"
            AttachmentRequirement.TEXT, AttachmentRequirement.DOCUMENT,
            AttachmentRequirement.VIDEO, AttachmentRequirement.AUDIO -> false
        }
    }

    fun check(
        descriptor: ModelCapabilityDescriptor,
        requirement: AttachmentRequirement,
        mimeType: String?,
        sizeBytes: Long?,
        count: Int = 1,
    ): AttachmentCompatibility {
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
        val tooMany = requirement == AttachmentRequirement.IMAGE && limit.maxImages?.let { count > it } == true
        val unsupportedMime = limit.supportedMimeTypes.isNotEmpty() && mimeType != null && mimeType !in limit.supportedMimeTypes
        val reason = when {
            descriptor.readiness.availability == ModelAvailability.UNAVAILABLE -> descriptor.readiness.reason.ifBlank { "النموذج غير متاح حاليًا." }
            descriptor.readiness.availability == ModelAvailability.UNKNOWN -> "لم يتم التحقق من توفر نموذج فعلي لهذا المسار."
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
            descriptor.readiness.availability != ModelAvailability.AVAILABLE ||
                descriptor.readiness.feasibility == ModelFeasibility.INSUFFICIENT_RESOURCES ||
                tooLarge || tooMany || unsupportedMime || status == CapabilityStatus.UNSUPPORTED ||
                status == CapabilityStatus.UNKNOWN || status == CapabilityStatus.TEMPORARILY_UNAVAILABLE -> CompatibilityDecision.BLOCK
            status == CapabilityStatus.SUPPORTED_WITH_LIMITS -> CompatibilityDecision.ALLOW_WITH_WARNING
            else -> CompatibilityDecision.ALLOW
        }
        return AttachmentCompatibility(decision, status, capability, reason, descriptor)
    }
}
