package com.airi.assistant.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CodeSyntaxHighlighterTest {
    @Test
    fun highlightingPreservesExactCodeTextAndAddsPresentationSpans() {
        val source = "import tkinter as tk\n# تعليق عربي\nprint(42, 'مرحبا')  "
        val result = CodeSyntaxHighlighter.highlight(source, "python")

        assertEquals(source, result.text)
        assertTrue(result.spanStyles.isNotEmpty())
        assertTrue(result.text.endsWith("  "))
    }
}
