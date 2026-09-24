package com.airi.assistant.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTitlePolicyTest {
    @Test fun arabicTaskBecomesShortTaskTitle() {
        assertEquals("إصلاح خطأ Kotlin أو Gradle", ConversationTitlePolicy.generate("أريد منك إصلاح مشكلة Gradle في AIRI"))
    }

    @Test fun englishWebsiteRequestKeepsTaskLanguage() {
        assertEquals("Create a website", ConversationTitlePolicy.generate("Please create a website for my portfolio"))
    }

    @Test fun urlHeavyPromptDoesNotPersistTheUrl() {
        val title = ConversationTitlePolicy.generate("حلل هذا الرابط https://example.com/a/very/long/path?token=secret")
        assertFalse(title.contains("http"))
        assertTrue(title.length <= 60)
    }

    @Test fun longInputIsBoundedAndMarkdownIsRemoved() {
        val title = ConversationTitlePolicy.generate("# ${"word ".repeat(50)}")
        assertTrue(title.length <= 60)
        assertFalse(title.contains('#'))
    }

    @Test fun blankInputUsesSafeFallback() {
        assertEquals("محادثة جديدة", ConversationTitlePolicy.generate("   "))
    }
}
