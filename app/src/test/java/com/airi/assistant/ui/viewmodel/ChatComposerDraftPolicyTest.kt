package com.airi.assistant.ui.viewmodel

import com.airi.assistant.domain.ChatAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatComposerDraftPolicyTest {

    @Test
    fun draftIsIsolatedToItsOwningSessionAndRestoredOnReturn() {
        val first = ChatComposerDraftPolicy.replaceText(
            drafts = emptyMap(),
            sessionId = "session-a",
            text = "Review this document",
        )
        val both = ChatComposerDraftPolicy.replaceText(
            drafts = first,
            sessionId = "session-b",
            text = "Prepare a release note",
        )

        assertEquals("Review this document", ChatComposerDraftPolicy.current(both, "session-a").text)
        assertEquals("Prepare a release note", ChatComposerDraftPolicy.current(both, "session-b").text)
        assertEquals(ChatComposerDraft(), ChatComposerDraftPolicy.current(both, "session-c"))
    }

    @Test
    fun attachmentDraftStaysWithItsSessionWhenAnotherSessionIsOpened() {
        val attachment = ChatAttachment(
            kind = ChatAttachment.Kind.FILE,
            displayName = "notes.txt",
            mimeType = "text/plain",
        )
        val drafts = ChatComposerDraftPolicy.replaceAttachments(
            drafts = emptyMap(),
            sessionId = "session-a",
            attachments = listOf(attachment),
        )

        assertEquals(listOf(attachment), ChatComposerDraftPolicy.current(drafts, "session-a").attachments)
        assertEquals(emptyList<ChatAttachment>(), ChatComposerDraftPolicy.current(drafts, "session-b").attachments)
    }

    @Test
    fun deletingASessionRemovesOnlyItsDraft() {
        val withBoth = mapOf(
            "session-a" to ChatComposerDraft(text = "keep only for A"),
            "session-b" to ChatComposerDraft(text = "keep only for B"),
        )

        val result = ChatComposerDraftPolicy.removeSession(withBoth, "session-a")

        assertFalse(result.containsKey("session-a"))
        assertEquals("keep only for B", ChatComposerDraftPolicy.current(result, "session-b").text)
    }

    @Test
    fun emptyDraftIsNotRetained() {
        val drafts = ChatComposerDraftPolicy.replaceText(
            drafts = mapOf("session-a" to ChatComposerDraft(text = "temporary")),
            sessionId = "session-a",
            text = "",
        )

        assertFalse(drafts.containsKey("session-a"))
    }
}
