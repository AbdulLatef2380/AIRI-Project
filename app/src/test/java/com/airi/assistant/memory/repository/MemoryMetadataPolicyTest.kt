package com.airi.assistant.memory.repository

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryMetadataPolicyTest {

    @Test
    fun projectScopeFallsBackToSessionWithoutProjectIdentity() {
        assertEquals(
            MemoryScope.SESSION.name,
            MemoryMetadataPolicy.normalizeScope(MemoryScope.PROJECT, "")
        )
        assertEquals(
            MemoryScope.PROJECT.name,
            MemoryMetadataPolicy.normalizeScope(MemoryScope.PROJECT, "project-42")
        )
        assertEquals(
            MemoryScope.USER.name,
            MemoryMetadataPolicy.normalizeScope(MemoryScope.USER, "project-42")
        )
    }

    @Test
    fun provenanceNeverPersistsSensitiveOrUnboundedText() {
        assertEquals(
            "Explicit user memory request",
            MemoryMetadataPolicy.sanitizeProvenance("source token: super-secret-value")
        )
        assertEquals(
            "Explicit request through Memory Agent",
            MemoryMetadataPolicy.sanitizeProvenance("  Explicit   request through Memory Agent  ")
        )
    }

    @Test
    fun privacyAndImportanceStayWithinContractBounds() {
        assertEquals(0, MemoryMetadataPolicy.normalizePrivacyLevel(-5))
        assertEquals(3, MemoryMetadataPolicy.normalizePrivacyLevel(9))
        assertEquals(0, MemoryMetadataPolicy.normalizeImportance(-1))
        assertEquals(100, MemoryMetadataPolicy.normalizeImportance(1000))
    }
}
