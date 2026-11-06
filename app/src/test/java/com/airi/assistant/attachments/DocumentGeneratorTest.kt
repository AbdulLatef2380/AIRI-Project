package com.airi.assistant.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipInputStream

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
        } finally { odtFile.delete(); epubFile.delete() }
    }

    @Test fun generatesValidDocxPackageAndReadableText() {
        val generated = DocumentGenerator.generate("report.docx", "# AIRI\nArabic مرحبا")
        assertEquals("application/vnd.openxmlformats-officedocument.wordprocessingml.document", generated.mimeType)
        val entries = zipEntries(generated.bytes)
        assertTrue("[Content_Types].xml" in entries)
        assertTrue("word/document.xml" in entries)
        val file = File.createTempFile("airi-generated-", ".docx")
        try {
            file.writeBytes(generated.bytes)
            assertTrue(AttachmentContentExtractor.extract(file, generated.mimeType, generated.fileName).contains("AIRI"))
        } finally { file.delete() }
    }

    @Test fun generatesXlsxFromCsvAndPreservesCells() {
        val generated = DocumentGenerator.generate("data.xlsx", "Name,Value\nAIRI,42")
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", generated.mimeType)
        val entries = zipEntries(generated.bytes)
        assertTrue("xl/workbook.xml" in entries)
        assertTrue("xl/worksheets/sheet1.xml" in entries)
        val file = File.createTempFile("airi-generated-", ".xlsx")
        try {
            file.writeBytes(generated.bytes)
            val extracted = AttachmentContentExtractor.extract(file, generated.mimeType, generated.fileName)
            assertTrue(extracted.contains("Name"))
            assertTrue(extracted.contains("42"))
        } finally { file.delete() }
    }

    private fun zipEntries(bytes: ByteArray): Set<String> = ZipInputStream(bytes.inputStream()).use { zip ->
        buildSet { while (true) { val entry = zip.nextEntry ?: break; add(entry.name) } }
    }
}
