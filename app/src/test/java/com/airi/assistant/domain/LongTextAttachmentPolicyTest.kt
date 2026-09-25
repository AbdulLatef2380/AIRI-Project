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

    @Test fun fifteenLinesStayFullyVisibleInline() {
        val text = (1..15).joinToString("\n") { "line $it" }
        assertFalse(LongTextAttachmentPolicy.shouldCollapseInline(text))
    }

    @Test fun sixteenToFortyLinesCollapseToFifteenLinePreview() {
        val text = (1..20).joinToString("\n") { "line $it" }
        assertTrue(LongTextAttachmentPolicy.shouldCollapseInline(text))
        assertTrue(LongTextAttachmentPolicy.collapsedPreview(text).lineSequence().count() == 15)
    }

    @Test fun attachmentThresholdTakesPrecedenceOverInlineCollapse() {
        val text = (1..41).joinToString("\n") { "line $it" }
        assertFalse(LongTextAttachmentPolicy.shouldCollapseInline(text))
        assertTrue(LongTextAttachmentPolicy.shouldAutoConvert(text))
    }

    @Test fun byteSizeUsesUtf8NotCharacterCount() {
        assertTrue(LongTextAttachmentPolicy.utf8SizeBytes("ع" ) > "ع".length)
    }
}
