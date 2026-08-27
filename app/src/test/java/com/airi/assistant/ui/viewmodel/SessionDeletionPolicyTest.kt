package com.airi.assistant.ui.viewmodel

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionDeletionPolicyTest {

    @Test
    fun failedDeletionKeepsTheComposerDraft() {
        assertFalse(SessionDeletionPolicy.shouldRemoveDraft(deleteSucceeded = false))
    }

    @Test
    fun successfulDeletionRemovesOnlyTheDeletedSessionDraft() {
        assertTrue(SessionDeletionPolicy.shouldRemoveDraft(deleteSucceeded = true))
    }

    @Test
    fun activeDeletedSessionUsesAnExistingReplacementOnlyAfterDeletion() {
        assertEquals(
            "session-b",
            SessionDeletionPolicy.replacementSessionId(
                activeSessionId = "session-a",
                deletedSessionId = "session-a",
                remainingSessionIds = listOf("session-b", "session-c"),
            ),
        )
    }

    @Test
    fun deletingAnInactiveSessionDoesNotSwitchTheCurrentConversation() {
        assertNull(
            SessionDeletionPolicy.replacementSessionId(
                activeSessionId = "session-a",
                deletedSessionId = "session-b",
                remainingSessionIds = listOf("session-a", "session-c"),
            ),
        )
    }
}
