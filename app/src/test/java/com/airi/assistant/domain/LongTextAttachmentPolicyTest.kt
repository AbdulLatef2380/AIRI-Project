package com.airi.assistant.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LongTextAttachmentPolicyTest {
    @Test fun shortTextStaysInline() {
        assertFalse(LongTextAttachmentPolicy.shouldAutoConvert("x".repeat(2_999)))
    }

    @Test fun characterBoundaryConvertsAt3000() {
        assertTrue(LongTextAttachmentPolicy.shouldAutoConvert("x".repeat(3_000)))
    }

    @Test fun fortyLinesConvertEvenWhenShort() {
        assertTrue(LongTextAttachmentPolicy.shouldAutoConvert((1..40).joinToString("\n") { "line" }))
    }

    @Test fun thirtyNineLinesStayInlineWhenShort() {
        assertFalse(LongTextAttachmentPolicy.shouldAutoConvert((1..39).joinToString("\n") { "line" }))
    }

    @Test fun byteSizeUsesUtf8NotCharacterCount() {
        assertTrue(LongTextAttachmentPolicy.utf8SizeBytes("ع" ) > "ع".length)
    }
}
