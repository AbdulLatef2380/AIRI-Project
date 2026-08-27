package com.airi.assistant.ui.viewmodel

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
    STAGING_FAILED,
}

internal object AttachmentDispatchPolicy {
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
