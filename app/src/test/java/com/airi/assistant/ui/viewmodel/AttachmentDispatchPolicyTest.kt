package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentDispatchPolicyTest {

    @Test
    fun preflightRejectsWhileTheModelIsLoading() {
        assertEquals(
            AttachmentDispatchFailure.MODEL_LOADING,
            AttachmentDispatchPolicy.preflight(
                modelLoading = true,
                generationInProgress = false,
                hasVisualImage = false,
                visionReady = true,
            ),
        )
    }

    @Test
    fun preflightRejectsDuringAnExistingGeneration() {
        assertEquals(
            AttachmentDispatchFailure.GENERATION_IN_PROGRESS,
            AttachmentDispatchPolicy.preflight(
                modelLoading = false,
                generationInProgress = true,
                hasVisualImage = false,
                visionReady = true,
            ),
        )
    }

    @Test
    fun visualAttachmentRequiresAVisionReadyModel() {
        assertEquals(
            AttachmentDispatchFailure.VISION_UNAVAILABLE,
            AttachmentDispatchPolicy.preflight(
                modelLoading = false,
                generationInProgress = false,
                hasVisualImage = true,
                visionReady = false,
            ),
        )
        assertNull(
            AttachmentDispatchPolicy.preflight(
                modelLoading = false,
                generationInProgress = false,
                hasVisualImage = true,
                visionReady = true,
            ),
        )
    }

    @Test
    fun stagingIsRejectedWhenTheOwningSessionChanges() {
        assertEquals(
            AttachmentDispatchFailure.SESSION_CHANGED,
            AttachmentDispatchPolicy.sessionOwnership(
                sessionAtDispatch = "session-a",
                currentSession = "session-b",
            ),
        )
        assertNull(
            AttachmentDispatchPolicy.sessionOwnership(
                sessionAtDispatch = "session-a",
                currentSession = "session-a",
            ),
        )
    }

    @Test
    fun missingSessionOwnershipIsRejectedFailClosed() {
        assertEquals(
            AttachmentDispatchFailure.SESSION_CHANGED,
            AttachmentDispatchPolicy.sessionOwnership(
                sessionAtDispatch = "",
                currentSession = "session-a",
            ),
        )
        assertEquals(
            AttachmentDispatchFailure.SESSION_CHANGED,
            AttachmentDispatchPolicy.sessionOwnership(
                sessionAtDispatch = "session-a",
                currentSession = "",
            ),
        )
    }

    @Test
    fun stagingFailureRemainsVisibleToTheComposer() {
        assertEquals(
            AttachmentDispatchFailure.STAGING_FAILED,
            AttachmentDispatchPolicy.afterStaging(allAttachmentsPersisted = false),
        )
        assertNull(AttachmentDispatchPolicy.afterStaging(allAttachmentsPersisted = true))
    }
}
