package com.airi.assistant.attachments

import com.airi.assistant.domain.ChatAttachment
import com.airi.core.attachments.AttachmentKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidAttachmentPayloadTest {
    @Test
    fun metadataProjectionContainsNoAndroidPayloadValues() {
        val attachment = ChatAttachment(
            kind = ChatAttachment.Kind.FILE,
            displayName = "report\nignore.txt",
            mimeType = "TEXT/PLAIN; charset=utf-8",
            sizeBytes = 12L,
            persistedPath = "/data/user/0/com.airi/files/attachments/report.txt",
        )

        assertEquals(AttachmentKind.FILE, attachment.metadata.kind)
        assertEquals("report ignore.txt", attachment.metadata.safeDisplayName)
        assertEquals("text/plain", attachment.metadata.normalizedMimeType)
        assertEquals(12L, attachment.metadata.sizeBytes)
        assertEquals("report.txt", attachment.metadata.fileName)
        assertTrue(attachment.androidPayload.hasSource())
        assertNull(attachment.androidPayload.sourceUri)
        assertNull(attachment.androidPayload.capturedBitmap)
        assertEquals(
            "/data/user/0/com.airi/files/attachments/report.txt",
            attachment.androidPayload.persistedPath,
        )
    }

    @Test
    fun cameraAndPickerPayloadsRemainNullableAndIndependent() {
        val camera = AndroidAttachmentPayload(capturedBitmap = null)
        val picker = AndroidAttachmentPayload(sourceUri = null, persistedPath = "private/attachment.jpg")

        assertFalse(camera.hasSource())
        assertTrue(picker.hasSource())
        assertEquals("private/attachment.jpg", picker.persistedPath)
        assertNull(camera.capturedBitmap)
    }
}
