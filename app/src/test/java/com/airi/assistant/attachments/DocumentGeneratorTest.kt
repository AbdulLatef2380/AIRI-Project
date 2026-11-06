package com.airi.assistant.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DocumentGeneratorTest {
    @Test fun generatesBoundedTextFormats() {
        val generated = DocumentGenerator.generate("report.md", "# AIRI")
        assertEquals("text/markdown", generated.mimeType)
        assertEquals("# AIRI", generated.bytes.toString(Charsets.UTF_8))
    }

    @Test fun generatedOdtAndEpubAreReadableBySharedExtractor() {
        val odt = DocumentGenerator.generate("report.odt", "ODF content")
        val odtFile = File.createTempFile("airi-generated-", ".odt")
        val epub = DocumentGenerator.generate("book.epub", "EPUB content")
        val epubFile = File.createTempFile("airi-generated-", ".epub")
        try {
            odtFile.writeBytes(odt.bytes)
            epubFile.writeBytes(epub.bytes)
            assertTrue(AttachmentContentExtractor.extract(odtFile, odt.mimeType, odt.fileName).contains("ODF content"))
            assertTrue(AttachmentContentExtractor.extract(epubFile, epub.mimeType, epub.fileName).contains("EPUB content"))
        } finally {
            odtFile.delete()
            epubFile.delete()
        }
    }
}
