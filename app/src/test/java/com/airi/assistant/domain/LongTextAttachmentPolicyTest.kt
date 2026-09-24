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

    @Test fun fortyLinesStayInlineWhenShort() {
        assertFalse(LongTextAttachmentPolicy.shouldAutoConvert((1..40).joinToString("\n") { "line" }))
    }

    @Test fun fortyOneLinesConvertWhenShort() {
        assertTrue(LongTextAttachmentPolicy.shouldAutoConvert((1..41).joinToString("\n") { "line" }))
    }

    @Test fun byteSizeUsesUtf8NotCharacterCount() {
        assertTrue(LongTextAttachmentPolicy.utf8SizeBytes("ع" ) > "ع".length)
    }
}
