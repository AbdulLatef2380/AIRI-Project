package com.airi.assistant.attachments

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DocumentFormatRegistryTest {
    @Test
    fun `known document formats expose only real capabilities`() {
        assertTrue(DocumentFormatRegistry.readable("application/vnd.oasis.opendocument.text", "notes.odt"))
        assertTrue(DocumentFormatRegistry.readable("application/epub+zip", "book.epub"))
        assertTrue(DocumentFormatRegistry.generatable("md"))
        assertFalse(DocumentFormatRegistry.generatable("pdf"))
        assertFalse(DocumentFormatRegistry.readable("application/msword", "legacy.doc"))
    }
}
