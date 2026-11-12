package com.airi.assistant.ui.viewmodel

import com.airi.assistant.attachments.AttachmentContentExtractor
import com.airi.assistant.domain.ChatAttachment
import com.airi.assistant.execution.AttachmentRequirement
import com.airi.assistant.execution.Capability
import com.airi.assistant.execution.CompatibilityDecision
import com.airi.assistant.execution.ModelCapabilityDescriptor
import com.airi.assistant.execution.ModelCapabilityEngine
import com.airi.assistant.execution.CloudProvider
import com.airi.core.attachments.AttachmentPolicy

/**
 * Pure admission coordinator for attachment dispatch.
 *
 * It decides whether a composed attachment request may enter Android staging.
 * It does not own Android context or payload handles, files, StateFlow, or sending.
 * Those remain in platform adapters and ChatViewModel execution wiring.
 */
internal class AttachmentDispatchCoordinator {
    data class Request(
        val attachments: List<ChatAttachment>,
        val modelLoading: Boolean,
        val generationInProgress: Boolean,
        val localAllowed: Boolean,
        val cloudAllowed: Boolean,
        val cloudCanReceiveAttachments: Boolean,
        val cloudAttachmentConsent: Boolean,
        val localVisionReady: Boolean,
        val cloudVisionReady: Boolean,
        val cloudDescriptor: ModelCapabilityDescriptor?,
        val cloudProvider: CloudProvider?,
        val candidateDescriptors: List<ModelCapabilityDescriptor>,
        val sessionId: String,
    )

    sealed interface Decision {
        data class Accepted(
            val imageRoute: ImageDispatchRoute?,
            val visionReady: Boolean,
            val cloudDescriptor: ModelCapabilityDescriptor?,
            val cloudProvider: CloudProvider?,
            val sessionId: String,
        ) : Decision

        data class Rejected(val failure: AttachmentDispatchFailure) : Decision
    }

    fun admit(request: Request): Decision {
        if (request.attachments.isEmpty()) {
            return Decision.Rejected(AttachmentDispatchFailure.DISPATCH_FAILED)
        }
        if (request.attachments.any { attachment ->
                AttachmentDispatchPolicy.payloadTransportFailure(
                    attachment.contentType,
                    attachment.normalizedMimeType,
                    attachment.safeDisplayName,
                ) != null
            }
        ) {
            return Decision.Rejected(AttachmentDispatchFailure.UNSUPPORTED_CONTENT)
        }
        AttachmentDispatchPolicy.cloudConsentFailure(
            request.cloudCanReceiveAttachments,
            request.cloudAttachmentConsent,
        )?.let { return Decision.Rejected(it) }

        val hasVisualImage = request.attachments.any { it.isVisualImage }
        val visionReady = request.localVisionReady || request.cloudVisionReady
        AttachmentDispatchPolicy.preflight(
            modelLoading = request.modelLoading,
            generationInProgress = request.generationInProgress,
            hasVisualImage = hasVisualImage,
            visionReady = visionReady,
        )?.let { return Decision.Rejected(it) }

        val visualImageCount = request.attachments.count { it.isVisualImage }
        val imageRoute = AttachmentDispatchPolicy.imageRoute(
            imageCount = visualImageCount,
            localVisionReady = request.localVisionReady,
            cloudVisionReady = request.cloudVisionReady,
            cloudMaxImages = request.cloudDescriptor?.limits?.maxImages ?: 0,
        )
        if (visualImageCount > 0 && imageRoute == null) {
            return Decision.Rejected(
                if (visualImageCount > 1) {
                    AttachmentDispatchFailure.MULTI_IMAGE_UNSUPPORTED
                } else {
                    AttachmentDispatchFailure.VISION_UNAVAILABLE
                }
            )
        }

        if (request.candidateDescriptors.isEmpty()) {
            return Decision.Rejected(AttachmentDispatchFailure.CAPABILITY_UNKNOWN)
        }
        val incompatible = request.attachments.firstNotNullOfOrNull { attachment ->
            val requirement = requirementFor(attachment)
            val results = request.candidateDescriptors.map { descriptor ->
                ModelCapabilityEngine.check(
                    descriptor = descriptor,
                    requirement = requirement,
                    mimeType = attachment.normalizedMimeType,
                    sizeBytes = attachment.sizeBytes,
                    count = request.attachments.count { it.contentType == attachment.contentType },
                )
            }
            val extractedTextAllowed = (
                attachment.isTextual || AttachmentContentExtractor.supports(
                    attachment.normalizedMimeType,
                    attachment.safeDisplayName,
                )
            ) && request.candidateDescriptors.any { descriptor ->
                ModelCapabilityEngine.check(
                    descriptor = descriptor,
                    requirement = AttachmentRequirement.TEXT,
                    mimeType = "text/plain",
                    sizeBytes = attachment.sizeBytes,
                ).decision == CompatibilityDecision.ALLOW
            }
            if (results.any {
                    it.decision == CompatibilityDecision.ALLOW ||
                        it.decision == CompatibilityDecision.ALLOW_WITH_WARNING
                } || extractedTextAllowed
            ) {
                null
            } else {
                results.first()
            }
        }
        if (incompatible != null) {
            return Decision.Rejected(
                when (incompatible.status) {
                    com.airi.assistant.execution.CapabilityStatus.UNKNOWN ->
                        AttachmentDispatchFailure.CAPABILITY_UNKNOWN
                    else -> if (
                        incompatible.capability == Capability.IMAGE_UNDERSTANDING
                    ) {
                        AttachmentDispatchFailure.VISION_UNAVAILABLE
                    } else {
                        AttachmentDispatchFailure.CAPABILITY_UNAVAILABLE
                    }
                }
            )
        }
        if (request.sessionId.isBlank()) {
            return Decision.Rejected(AttachmentDispatchFailure.SESSION_CHANGED)
        }
        return Decision.Accepted(
            imageRoute = imageRoute,
            visionReady = visionReady,
            cloudDescriptor = request.cloudDescriptor,
            cloudProvider = request.cloudProvider,
            sessionId = request.sessionId,
        )
    }

    private fun requirementFor(attachment: ChatAttachment): AttachmentRequirement = when (attachment.contentType) {
        AttachmentPolicy.ContentType.IMAGE -> AttachmentRequirement.IMAGE
        AttachmentPolicy.ContentType.VIDEO -> AttachmentRequirement.VIDEO
        AttachmentPolicy.ContentType.TEXT -> AttachmentRequirement.TEXT
        AttachmentPolicy.ContentType.DOCUMENT,
        AttachmentPolicy.ContentType.FILE -> if (
            attachment.normalizedMimeType.equals("application/pdf", ignoreCase = true)
        ) {
            AttachmentRequirement.PDF
        } else {
            AttachmentRequirement.DOCUMENT
        }
    }
}
