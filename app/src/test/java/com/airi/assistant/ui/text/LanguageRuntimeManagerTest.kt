package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRuntimeManagerTest {
    @Test
    fun detectsArabicEnglishAndMixedContent() {
        assertEquals(LanguageRuntimeManager.DominantDirection.RTL, LanguageRuntimeManager.analyseDirection("مرحبا بالعالم"))
        assertEquals(LanguageRuntimeManager.DominantDirection.LTR, LanguageRuntimeManager.analyseDirection("Provider error 429"))
        assertEquals(LanguageRuntimeManager.DominantDirection.MIXED, LanguageRuntimeManager.analyseDirection("تعذر الاتصال بـ Gemini"))
    }

    @Test
    fun isolatesTechnicalLatinRunsInsideArabic() {
        val input = "تعذر الاتصال بـ Provider: Gemini HTTP 429"
        val formatted = LanguageRuntimeManager.isolateLatinRuns(input)

        assertTrue(formatted.contains("\u2066Provider"))
        assertTrue(formatted.contains("\u2066Gemini"))
        assertTrue(formatted.contains("HTTP 429\u2069"))
        assertEquals(input, LanguageRuntimeManager.stripBidiMarks(formatted))
    }

    @Test
    fun punctuationAndIdentifiersRemainPresentAfterBidiProcessing() {
        val input = "خطأ: https://example.com/api/v1?id=42 code_1()"
        val formatted = LanguageRuntimeManager.isolateLatinRuns(input)

        assertTrue(formatted.contains("https"))
        assertTrue(formatted.contains("example.com/api/v1"))
        assertTrue(formatted.contains("code_1"))
        assertTrue(formatted.contains(":"))
        assertEquals(input, LanguageRuntimeManager.stripBidiMarks(formatted))
    }

    @Test
    fun isolatesProseWithoutMutatingInlineOrFencedCode() {
        val inlineCode = "`println(1 + 1)`"
        val fencedCode = "```python\nimport tkinter as tk\nprint('مرحبا')\n```"
        val input = "شرح عربي حول $inlineCode ثم:\n$fencedCode\nبعد الكود Provider"

        val formatted = LanguageRuntimeManager.isolateLatinRuns(input)

        assertTrue(formatted.contains(inlineCode))
        assertTrue(formatted.contains(fencedCode))
        assertEquals(input, LanguageRuntimeManager.stripBidiMarks(formatted))
    }

    @Test
    fun preservesAnUnclosedStreamingCodeFence() {
        val partial = "```python\nprint('مرحبا')"
        val input = "شرح عربي\n$partial"

        assertTrue(LanguageRuntimeManager.isolateLatinRuns(input).endsWith(partial))
    }
}
