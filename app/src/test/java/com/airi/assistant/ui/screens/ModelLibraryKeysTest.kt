package com.airi.assistant.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelLibraryKeysTest {
    @Test
    fun stableCatalogKeysDisambiguatesDuplicateAndBlankIds() {
        assertEquals(
            listOf("model", "model#1", "item", "item#1", "other"),
            stableCatalogKeys(listOf("model", "model", "", "", "other"))
        )
    }
}
