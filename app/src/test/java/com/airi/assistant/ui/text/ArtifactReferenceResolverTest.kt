package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ArtifactReferenceResolverTest {
    @Test
    fun extractsOnlyBoundedInternalArtifactIdsAndDeduplicates() {
        val text = buildString {
            append("[report](airi://artifact/abc_123) and airi://artifact/abc_123 ")
            append("airi://artifact/valid-2 ")
            append("airi://artifact/../../secret")
        }

        assertEquals(listOf("abc_123", "valid-2"), ArtifactReferenceResolver.extractIds(text))
    }

    @Test
    fun rejectsUnscopedProjectReferences() {
        assertEquals(emptyList<String>(), ArtifactReferenceResolver.extractIds("airi://artifact/"))
    }

    @Test
    fun capsReferencesToPreventUnboundedUiExpansion() {
        val text = (1..20).joinToString(" ") { "airi://artifact/id_$it" }
        assertEquals(8, ArtifactReferenceResolver.extractIds(text).size)
    }

    @Test
    fun blankProjectScopeNeverProducesUsableReference() {
        val result = ArtifactReferenceResolver.resolveDetailed(
            text = "airi://artifact/owned",
            projectId = "",
            artifactManager = error("manager must not be queried for blank scope"),
        )

        assertEquals(ArtifactReferenceState.INVALID_SCOPE, result.single().state)
        assertFalse(result.single().isUsable)
    }
}
