package com.airi.assistant.memory.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryTextNormalizerTest {

    @Test
    fun tokensRetainArabicWordsAfterPunctuationSplitting() {
        val tokens = MemoryTextNormalizer.tokens("أعملُ في الجامعة، وأدرس البرمجة.")

        assertTrue(tokens.contains("اعمل"))
        assertTrue(tokens.contains("جامعة"))
        assertTrue(tokens.contains("برمجة"))
    }

    @Test
    fun tokensNormalizeArabicAlefFormsAndDefiniteArticle() {
        val tokens = MemoryTextNormalizer.tokens("إدارة المدرسة وآثار التعليم")

        assertEquals(setOf("ادارة", "مدرسة", "واثار", "تعليم"), tokens)
    }
}
