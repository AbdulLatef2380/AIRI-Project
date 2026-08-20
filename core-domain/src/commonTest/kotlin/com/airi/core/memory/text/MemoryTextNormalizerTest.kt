package com.airi.core.memory.text

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
