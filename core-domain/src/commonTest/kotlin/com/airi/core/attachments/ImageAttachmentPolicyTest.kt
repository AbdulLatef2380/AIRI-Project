package com.airi.core.attachments

import kotlin.test.Test
import kotlin.test.assertEquals

class ImageAttachmentPolicyTest {

    @Test
    fun `accepts image within dimension and decoded memory limits`() {
        assertEquals(
            ImageAttachmentPolicy.ValidationResult.Accepted,
            ImageAttachmentPolicy.validate(width = 4_096, height = 4_096)
        )
    }

    @Test
    fun `rejects invalid oversized and decompression risk images`() {
        assertEquals(
            ImageAttachmentPolicy.ValidationResult.InvalidDimensions,
            ImageAttachmentPolicy.validate(width = 0, height = 100)
        )
        assertEquals(
            ImageAttachmentPolicy.ValidationResult.DimensionsTooLarge,
            ImageAttachmentPolicy.validate(width = 8_193, height = 1)
        )
        assertEquals(
            ImageAttachmentPolicy.ValidationResult.DecodedImageTooLarge,
            ImageAttachmentPolicy.validate(width = 8_192, height = 8_192)
        )
    }
}
