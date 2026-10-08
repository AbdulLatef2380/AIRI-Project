package com.airi.assistant.ui.viewmodel

import com.airi.core.attachments.AttachmentPolicy
import com.airi.assistant.attachments.AttachmentContentExtractor

/**
 * Admission policy for a composed message that contains attachments.
 *
 * The policy is intentionally independent from Android storage and inference so
 * both the ViewModel and unit tests can make the same decision before the UI
 * clears a user's staged attachments.
 */
enum class AttachmentDispatchFailure {
    MODEL_LOADING,
    GENERATION_IN_PROGRESS,
    SESSION_CHANGED,
    VISION_UNAVAILABLE,
    CAPABILITY_UNAVAILABLE,
    CAPABILITY_UNKNOWN,
    STAGING_FAILED,
    UNSUPPORTED_CONTENT,
    TEXT_EXTRACTION_FAILED,
    MULTI_IMAGE_UNSUPPORTED,
    ATTACHMENT_TOO_LARGE,
    ATTACHMENT_BATCH_TOO_LARGE,
    TEXT_ATTACHMENT_TOO_LARGE,
    CLOUD_ATTACHMENT_CONSENT_REQUIRED,
    DISPATCH_FAILED,
}

internal enum class ImageDispatchRoute { LOCAL_SINGLE_IMAGE, CLOUD_VISION }

internal object AttachmentDispatchPolicy {
    const val MAX_TOTAL_ATTACHMENT_BYTES = 40L * 1024L * 1024L

    /** A capability declaration is not sufficient unless this runtime has a payload path. */
    fun payloadTransportFailure(
        contentType: AttachmentPolicy.ContentType,
        mimeType: String = "",
        fileName: String = "",
    ): AttachmentDispatchFailure? = when (contentType) {
        AttachmentPolicy.ContentType.IMAGE,
        AttachmentPolicy.ContentType.TEXT -> null
        // Transport is provider/model-specific. Defer the decision to
        // ModelCapabilityEngine so native Gemini media can proceed while
        // unsupported local/OpenAI-compatible targets still fail closed.
        AttachmentPolicy.ContentType.DOCUMENT,
        AttachmentPolicy.ContentType.FILE,
        AttachmentPolicy.ContentType.VIDEO -> null
    }

    fun maximumSizeBytes(contentType: AttachmentPolicy.ContentType): Long =
        if (contentType == AttachmentPolicy.ContentType.TEXT) {
            AttachmentPolicy.MAX_TEXT_ATTACHMENT_BYTES
        } else {
            AttachmentPolicy.MAX_ATTACHMENT_BYTES
        }

    fun sizeFailure(
        actualSizeBytes: Long,
        contentType: AttachmentPolicy.ContentType,
    ): AttachmentDispatchFailure? = when (AttachmentPolicy.validateSize(actualSizeBytes, contentType)) {
        AttachmentPolicy.ValidationResult.Accepted -> null
        AttachmentPolicy.ValidationResult.TooLarge -> AttachmentDispatchFailure.ATTACHMENT_TOO_LARGE
        AttachmentPolicy.ValidationResult.TextTooLarge -> AttachmentDispatchFailure.TEXT_ATTACHMENT_TOO_LARGE
    }

    fun imageRoute(
        imageCount: Int,
        localVisionReady: Boolean,
        cloudVisionReady: Boolean,
        cloudMaxImages: Int,
    ): ImageDispatchRoute? = when {
        imageCount <= 0 -> null
        imageCount == 1 && localVisionReady -> ImageDispatchRoute.LOCAL_SINGLE_IMAGE
        cloudVisionReady && imageCount <= cloudMaxImages -> ImageDispatchRoute.CLOUD_VISION
        else -> null
    }

    fun preflight(
        modelLoading: Boolean,
        generationInProgress: Boolean,
        hasVisualImage: Boolean,
        visionReady: Boolean,
    ): AttachmentDispatchFailure? = when {
        modelLoading -> AttachmentDispatchFailure.MODEL_LOADING
        generationInProgress -> AttachmentDispatchFailure.GENERATION_IN_PROGRESS
        hasVisualImage && !visionReady -> AttachmentDispatchFailure.VISION_UNAVAILABLE
        else -> null
    }

    fun afterStaging(allAttachmentsPersisted: Boolean): AttachmentDispatchFailure? =
        if (allAttachmentsPersisted) null else AttachmentDispatchFailure.STAGING_FAILED

    fun cloudConsentFailure(cloudCanReceiveAttachments: Boolean, oneShotConsent: Boolean): AttachmentDispatchFailure? =
        if (cloudCanReceiveAttachments && !oneShotConsent) AttachmentDispatchFailure.CLOUD_ATTACHMENT_CONSENT_REQUIRED else null

    fun sessionOwnership(
        sessionAtDispatch: String,
        currentSession: String,
    ): AttachmentDispatchFailure? = if (
        sessionAtDispatch.isBlank() || currentSession.isBlank() || sessionAtDispatch != currentSession
    ) {
        AttachmentDispatchFailure.SESSION_CHANGED
    } else {
        null
    }
}
