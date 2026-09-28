package com.airi.assistant.ui.viewmodel

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

class AttachmentSizeGuardTest {
    @Test
    fun exactLimitIsCopied() {
        val source = byteArrayOf(1, 2, 3, 4)
        val output = ByteArrayOutputStream()

        copyAttachmentBounded(
            input = ByteArrayInputStream(source),
            output = output,
            maxBytes = source.size.toLong(),
            tooLargeFailure = AttachmentDispatchFailure.ATTACHMENT_TOO_LARGE,
        )

        assertArrayEquals(source, output.toByteArray())
    }

    @Test
    fun extraByteIsRejectedBeforeItIsWritten() {
        val output = ByteArrayOutputStream()
        try {
            copyAttachmentBounded(
                input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4)),
                output = output,
                maxBytes = 3L,
                tooLargeFailure = AttachmentDispatchFailure.TEXT_ATTACHMENT_TOO_LARGE,
            )
            fail("Expected actual-size overrun to be rejected")
        } catch (error: AttachmentSizeLimitException) {
            assertEquals(AttachmentDispatchFailure.TEXT_ATTACHMENT_TOO_LARGE, error.dispatchFailure)
            assertArrayEquals(byteArrayOf(), output.toByteArray())
        }
    }
}
