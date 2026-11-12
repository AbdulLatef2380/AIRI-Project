package com.airi.assistant.ui.viewmodel

import com.airi.assistant.domain.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Test

class AttachmentDispatchCoordinatorTest {
    private val coordinator = AttachmentDispatchCoordinator()

    private fun request(
        attachment: ChatAttachment = ChatAttachment(
            kind = ChatAttachment.Kind.FILE,
            displayName = "notes.txt",
            mimeType = "text/plain",
            sizeBytes = 10L,
        ),
        modelLoading: Boolean = false,
        generationInProgress: Boolean = false,
        cloudCanReceiveAttachments: Boolean = false,
        cloudAttachmentConsent: Boolean = false,
        sessionId: String = "session-a",
    ) = AttachmentDispatchCoordinator.Request(
        attachments = listOf(attachment),
        modelLoading = modelLoading,
        generationInProgress = generationInProgress,
        localAllowed = true,
        cloudAllowed = false,
        cloudCanReceiveAttachments = cloudCanReceiveAttachments,
        cloudAttachmentConsent = cloudAttachmentConsent,
        localVisionReady = false,
        cloudVisionReady = false,
        cloudDescriptor = null,
        cloudProvider = null,
        candidateDescriptors = emptyList(),
        sessionId = sessionId,
    )

    @Test
    fun rejectsWhileModelIsLoadingBeforeCapabilityAdmission() {
        val decision = coordinator.admit(request(modelLoading = true))
        assertEquals(
            AttachmentDispatchFailure.MODEL_LOADING,
            (decision as AttachmentDispatchCoordinator.Decision.Rejected).failure,
        )
    }

    @Test
    fun rejectsGenerationInProgressBeforeStaging() {
        val decision = coordinator.admit(request(generationInProgress = true))
        assertEquals(
            AttachmentDispatchFailure.GENERATION_IN_PROGRESS,
            (decision as AttachmentDispatchCoordinator.Decision.Rejected).failure,
        )
    }

    @Test
    fun requiresOneShotConsentBeforeCloudStaging() {
        val decision = coordinator.admit(
            request(cloudCanReceiveAttachments = true, cloudAttachmentConsent = false)
        )
        assertEquals(
            AttachmentDispatchFailure.CLOUD_ATTACHMENT_CONSENT_REQUIRED,
            (decision as AttachmentDispatchCoordinator.Decision.Rejected).failure,
        )
    }

    @Test
    fun rejectsUnknownCapabilityBeforeAnyStaging() {
        val decision = coordinator.admit(request(sessionId = ""))
        assertEquals(
            AttachmentDispatchFailure.CAPABILITY_UNKNOWN,
            (decision as AttachmentDispatchCoordinator.Decision.Rejected).failure,
        )
    }

    @Test
    fun rejectsEmptyAttachmentListWithoutCallingTheStager() {
        val decision = coordinator.admit(request().copy(attachments = emptyList()))
        assertEquals(
            AttachmentDispatchFailure.DISPATCH_FAILED,
            (decision as AttachmentDispatchCoordinator.Decision.Rejected).failure,
        )
    }
}
