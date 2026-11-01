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

    @Test
    fun highlightingClassifiesKeywordsFunctionsTypesAndOperators() {
        val source = "fun greet(name: String) { return name + \"!\" }"
        val palette = CodeSyntaxHighlighter.palette(CodeSyntaxHighlighter.defaultColor)
        val result = CodeSyntaxHighlighter.highlight(source, "kotlin", palette)

        fun hasColor(token: String, color: androidx.compose.ui.graphics.Color): Boolean {
            val start = result.text.indexOf(token)
            return result.spanStyles.any { range ->
                range.item.color == color && start >= range.start && start < range.end
            }
        }

        assertTrue(hasColor("fun", palette.keyword))
        assertTrue(hasColor("greet", palette.function))
        assertTrue(hasColor("String", palette.type))
        assertTrue(hasColor("+", palette.operator))
    }
}
