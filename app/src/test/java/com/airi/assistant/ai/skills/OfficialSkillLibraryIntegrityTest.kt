package com.airi.assistant.ai.skills

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfficialSkillLibraryIntegrityTest {
    @Test
    fun officialCatalogHasUniqueExecutableEntriesAndCompleteMetadata() {
        val entries = OfficialSkillLibrary.ALL
        val ids = entries.map { it.manifest.id }

        assertTrue("The built-in catalog must contain the requested real skill batches", entries.size >= 60)
        assertEquals("Official skill IDs must be unique", ids.size, ids.toSet().size)
        assertFalse(ids.any { it.isBlank() })
        entries.forEach { entry ->
            val manifest = entry.manifest
            assertEquals(manifest.id, manifest.id.trim())
            assertTrue(manifest.name.isNotBlank())
            assertTrue(manifest.displayName.isNotBlank())
            assertTrue(manifest.description.isNotBlank())
            assertTrue(manifest.version.matches(Regex("^\\d+\\.\\d+\\.\\d+.*$")))
            assertTrue(manifest.inputSchema.isNotEmpty())
            assertTrue(manifest.outputSchema.isNotEmpty())
            assertTrue(manifest.instructions.isNotBlank())
            assertTrue(manifest.examples.isNotEmpty())
            assertTrue(manifest.limitations.isNotEmpty())
            assertTrue(entry.factory != null)
        }
    }

    @Test
    fun requestedPhaseFiveBatchesAreRegisteredInTheCanonicalCatalog() {
        val ids = OfficialSkillLibrary.ids.toSet()
        assertTrue(ids.contains("document_reader"))
        assertTrue(ids.contains("file_manager"))
        assertTrue(ids.contains("task_planner"))
        assertTrue(ids.contains("meeting_agenda"))
        assertTrue(ids.contains("memory_manager"))
        assertTrue(ids.contains("requirements_extractor"))
        assertTrue(ids.contains("fact_checker"))
        assertTrue(ids.contains("test_strategy"))
    }
}
