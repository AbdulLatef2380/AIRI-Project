package com.airi.assistant.attachments

import com.airi.assistant.agent.loop.tool.BuiltinTools
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UnifiedArtifactContractTest {
    @Test
    fun allUnifiedArtifactToolsAreRegistered() {
        val names = BuiltinTools.ALL.map { it.name }.toSet()
        assertTrue(names.containsAll(setOf(
            "generate_document", "generate_spreadsheet", "generate_pdf",
            "generate_image", "generate_audio", "generate_video", "compress_files",
        )))
    }

    @Test
    fun pdfGeneratorProducesPhysicalPdfSignatureAndCrossReference() {
        val generated = DocumentGenerator.generate("report.pdf", "AIRI report\nSecond line")
        assertEquals("application/pdf", generated.mimeType)
        val text = generated.bytes.toString(Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.4"))
        assertTrue(text.contains("xref"))
        assertTrue(text.endsWith("%%EOF\n"))
        assertTrue(generated.bytes.isNotEmpty())
    }

    @Test(expected = IllegalArgumentException::class)
    fun unsupportedDocumentFormatCannotReportSuccess() {
        DocumentGenerator.generate("missing-format.bin", "content")
    }
}
