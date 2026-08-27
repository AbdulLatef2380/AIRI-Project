package com.airi.assistant.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatSessionActionPolicyTest {

    @Test
    fun emptyAutomaticSessionDoesNotExposeTranscriptOrManagementActions() {
        val actions = ChatSessionActionPolicy.availability(
            hasPersistedSession = true,
            sessionId = "session-a",
            messageCount = 0,
            title = "New Chat",
        )

        assertFalse(actions.canShareOrExport)
        assertFalse(actions.canPinOrRename)
    }

    @Test
    fun explicitTitleMayBeManagedButNotSharedWithoutMessages() {
        val actions = ChatSessionActionPolicy.availability(
            hasPersistedSession = true,
            sessionId = "session-a",
            messageCount = 0,
            title = "Release notes",
        )

        assertFalse(actions.canShareOrExport)
        assertTrue(actions.canPinOrRename)
    }

    @Test
    fun sessionWithMessagesExposesAllRelevantActions() {
        val actions = ChatSessionActionPolicy.availability(
            hasPersistedSession = true,
            sessionId = "session-a",
            messageCount = 2,
            title = "New Chat",
        )

        assertTrue(actions.canShareOrExport)
        assertTrue(actions.canPinOrRename)
    }

    @Test
    fun missingPersistedSessionCannotExposeActions() {
        val actions = ChatSessionActionPolicy.availability(
            hasPersistedSession = false,
            sessionId = "",
            messageCount = 3,
            title = "Imported",
        )

        assertFalse(actions.canShareOrExport)
        assertFalse(actions.canPinOrRename)
    }
}
