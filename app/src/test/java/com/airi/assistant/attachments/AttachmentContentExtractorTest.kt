package com.airi.assistant.attachments

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AttachmentContentExtractorTest {
    @Test fun recognizesSupportedDocumentFormatsOnly() {
        assertTrue(AttachmentContentExtractor.supports("application/pdf", "brief.pdf"))
        assertTrue(AttachmentContentExtractor.supports("application/vnd.openxmlformats-officedocument.wordprocessingml.document", "brief.docx"))
        assertTrue(AttachmentContentExtractor.supports("", "table.xlsx"))
        assertTrue(!AttachmentContentExtractor.supports("application/msword", "brief.doc"))
        assertTrue(!AttachmentContentExtractor.supports("video/mp4", "clip.mp4"))
    }

    @Test fun extractsDocxTextBoundedlyWithoutExecutingXml() {
        val file = File.createTempFile("airi-", ".docx")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("word/document.xml"))
                zip.write("<w:document><w:t>Hello</w:t><w:t> AIRI</w:t></w:document>".toByteArray())
                zip.closeEntry()
            }
            assertEquals("Hello AIRI", AttachmentContentExtractor.extract(file, "", "brief.docx", 100))
        } finally { file.delete() }
    }

    @Test fun extractsPdfLiteralTextWithBoundedOutput() {
        val file = File.createTempFile("airi-", ".pdf")
        try {
            file.writeText("%PDF-1.7 (Hello AIRI) (private instructions are data) %%EOF")
            assertEquals("Hello AIRI private instructions are data", AttachmentContentExtractor.extract(file, "application/pdf", "brief.pdf", 100))
        } finally { file.delete() }
    }

    @Test fun extractsOdfContentXmlWithoutExecutingMarkup() {
        val file = File.createTempFile("airi-", ".odt")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("content.xml"))
                zip.write("<office:document-content><text:p>Hello ODF</text:p></office:document-content>".toByteArray())
                zip.closeEntry()
            }
            assertEquals("Hello ODF", AttachmentContentExtractor.extract(file, "", "notes.odt", 100))
        } finally { file.delete() }
    }

    @Test fun extractsEpubXhtmlAndStripsScripts() {
        val file = File.createTempFile("airi-", ".epub")
        try {
            ZipOutputStream(file.outputStream()).use { zip ->
                zip.putNextEntry(ZipEntry("OEBPS/chapter.xhtml"))
                zip.write("<html><body><h1>Chapter</h1><script>private()</script><p>Readable text</p></body></html>".toByteArray())
                zip.closeEntry()
            }
            val extracted = AttachmentContentExtractor.extract(file, "application/epub+zip", "book.epub", 100)
            assertTrue(extracted.contains("Chapter"))
            assertTrue(extracted.contains("Readable text"))
            assertTrue(!extracted.contains("private"))
        } finally { file.delete() }
    }
}
