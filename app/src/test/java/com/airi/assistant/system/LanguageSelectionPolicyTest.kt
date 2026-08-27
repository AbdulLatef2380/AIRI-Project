package com.airi.assistant.system

import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageSelectionPolicyTest {
    @Test
    fun `arabic is the default for missing or invalid language`() {
        assertEquals("ar", LanguageSelectionPolicy.sanitize(null))
        assertEquals("ar", LanguageSelectionPolicy.sanitize(""))
        assertEquals("ar", LanguageSelectionPolicy.sanitize("fr"))
    }

    @Test
    fun `supported explicit language remains selected`() {
        assertEquals("en", LanguageSelectionPolicy.sanitize("en"))
        assertEquals("es", LanguageSelectionPolicy.sanitize("es"))
        assertEquals("zh", LanguageSelectionPolicy.sanitize("zh"))
        assertEquals("ar", LanguageSelectionPolicy.sanitize("ar"))
    }
}
