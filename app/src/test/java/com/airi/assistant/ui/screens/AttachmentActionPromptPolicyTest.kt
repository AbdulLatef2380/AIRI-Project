package com.airi.assistant.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentActionPromptPolicyTest {
    @Test
    fun emptyComposerReceivesTheActionContract() {
        assertEquals(
            "أنشئ عرضًا تقديميًا كاملًا:",
            AttachmentActionPromptPolicy.merge("", "أنشئ عرضًا تقديميًا كاملًا:")
        )
    }

    @Test
    fun existingUserRequestIsPreservedBeforeTheActionContract() {
        val result = AttachmentActionPromptPolicy.merge(
            "عن الأمن السيبراني",
            "أنشئ عرضًا تقديميًا كاملًا:"
        )
        assertEquals("عن الأمن السيبراني\n\nأنشئ عرضًا تقديميًا كاملًا:", result)
    }

    @Test
    fun blankActionDoesNotChangeTheComposer() {
        assertEquals("طلب المستخدم", AttachmentActionPromptPolicy.merge("طلب المستخدم", "  "))
    }

    @Test
    fun repeatedActionInsertionRemainsReadable() {
        val result = AttachmentActionPromptPolicy.merge(
            AttachmentActionPromptPolicy.merge("الفكرة", "أنشئ موقعًا متكاملًا:"),
            "تحقق من الملفات والاختبارات:"
        )
        assertTrue(result.startsWith("الفكرة\n\nأنشئ موقعًا متكاملًا:"))
        assertTrue(result.endsWith("تحقق من الملفات والاختبارات:"))
    }
}
