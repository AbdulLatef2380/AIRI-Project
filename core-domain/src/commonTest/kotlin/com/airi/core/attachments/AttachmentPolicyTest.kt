package com.airi.core.attachments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttachmentPolicyTest {

    @Test
    fun classifiesTextVideoAndDocumentAttachments() {
        assertEquals(
            AttachmentPolicy.ContentType.TEXT,
            AttachmentPolicy.contentType("text/plain", "notes.txt")
        )
        assertEquals(
            AttachmentPolicy.ContentType.VIDEO,
            AttachmentPolicy.contentType("video/mp4", "clip.mp4")
        )
        assertEquals(
            AttachmentPolicy.ContentType.DOCUMENT,
            AttachmentPolicy.contentType("application/pdf", "brief.pdf")
        )
    }

    @Test
    fun rejectsOversizedAttachmentsUsingTheirContentPolicy() {
        assertEquals(
            AttachmentPolicy.ValidationResult.TooLarge,
            AttachmentPolicy.validateSize(
                AttachmentPolicy.MAX_ATTACHMENT_BYTES + 1,
                AttachmentPolicy.ContentType.FILE
            )
        )
        assertEquals(
            AttachmentPolicy.ValidationResult.TextTooLarge,
            AttachmentPolicy.validateSize(
                AttachmentPolicy.MAX_TEXT_ATTACHMENT_BYTES + 1,
                AttachmentPolicy.ContentType.TEXT
            )
        )
    }

    @Test
    fun recognizesOnlyTheSameNonBlankUriAsADuplicateSource() {
        assertTrue(
            AttachmentPolicy.isSameSource(
                "content://provider/document/42",
                "content://provider/document/42"
            )
        )
        assertFalse(AttachmentPolicy.isSameSource("content://provider/document/42", "content://provider/document/43"))
        assertFalse(AttachmentPolicy.isSameSource(null, "content://provider/document/42"))
    }
}
