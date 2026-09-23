package com.airi.assistant.ai.skills

import com.airi.assistant.ai.skills.impl.PdfLiteralTextExtractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.system.measureTimeMillis

class PdfLiteralTextExtractorTest {
    @Test
    fun extractsLiteralTextAndRespectsCharacterBound() {
        val pdfLike = "%PDF-1.7 (Invoice number 42) (Total 125.50) %%EOF".toByteArray()
        assertEquals("Invoice number 42 Total 125.50", PdfLiteralTextExtractor.extract(pdfLike, 100))
        assertEquals("Invoice", PdfLiteralTextExtractor.extract(pdfLike, 7))
    }

    @Test
    fun scansAtMostEightMegabytesAndCompletesWithinBudget() {
        val input = ByteArray(PdfLiteralTextExtractor.MAX_SCAN_BYTES + 1024) { 'x'.code.toByte() }
        val elapsed = measureTimeMillis {
            val result = PdfLiteralTextExtractor.extract(input, 12_000)
            assertTrue(result.isEmpty())
        }
        assertTrue("bounded PDF scan was unexpectedly slow: ${elapsed}ms", elapsed < 1_500)
    }
}
