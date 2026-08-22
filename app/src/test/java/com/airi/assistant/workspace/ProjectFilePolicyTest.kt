package com.airi.assistant.workspace

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectFilePolicyTest {

    @Test
    fun normalizesUnsafeOrBlankNamesWithoutChangingSafeExtensions() {
        assertEquals("report_2026_.md", ProjectFilePolicy.normalizeFileName(" report/2026?.md "))
        assertEquals("imported-file", ProjectFilePolicy.normalizeFileName("   "))
        assertEquals("notes.txt", ProjectFilePolicy.normalizeFileName("notes.txt"))
    }

    @Test
    fun onlyDeclaredTextFormatsEnterExtractionPipeline() {
        assertEquals(
            ProjectFileManager.ExtractionState.PENDING,
            ProjectFilePolicy.extractionStateFor("text/markdown")
        )
        assertEquals(
            ProjectFileManager.ExtractionState.PENDING,
            ProjectFilePolicy.extractionStateFor("application/json")
        )
        assertEquals(
            ProjectFileManager.ExtractionState.NOT_APPLICABLE,
            ProjectFilePolicy.extractionStateFor("image/jpeg")
        )
        assertTrue(ProjectFilePolicy.isTextualApplicationType("application/sql"))
        assertFalse(ProjectFilePolicy.isTextualApplicationType("application/pdf"))
    }
}
