package com.airi.core.attachments

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AttachmentMetadataTest {
    @Test
    fun metadataNormalizesDisplayNameAndMimeWithoutPlatformPayload() {
        val metadata = AttachmentMetadata(
            id = "attachment-1",
            kind = AttachmentKind.FILE,
            displayName = "report\nignore.txt",
            mimeType = " TEXT/PLAIN; charset=utf-8 ",
            sizeBytes = 12L,
            uid = "attachment-1",
            fileName = "report.txt",
        )

        assertEquals("report ignore.txt", metadata.safeDisplayName)
        assertEquals("text/plain", metadata.normalizedMimeType)
        assertEquals("report.txt", metadata.fileName)
    }

    @Test
    fun metadataRejectsInvalidIdentityAndSize() {
        assertFailsWith<IllegalArgumentException> {
            AttachmentMetadata("", AttachmentKind.FILE, "file.txt", "text/plain", 1L, "uid", "file.txt")
        }
        assertFailsWith<IllegalArgumentException> {
            AttachmentMetadata("id", AttachmentKind.FILE, "file.txt", "text/plain", -1L, "uid", "file.txt")
        }
    }
}
