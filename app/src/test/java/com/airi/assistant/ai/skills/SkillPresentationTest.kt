package com.airi.assistant.ai.skills

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillPresentationTest {
    @Test
    fun everyCanonicalSkillHasArabicDisplayCopy() {
        val missing = OfficialSkillLibrary.ids.filterNot(SkillPresentation::hasArabicCopy)
        assertTrue("Missing Arabic copy for: $missing", missing.isEmpty())
    }

    @Test
    fun arabicPresentationKeepsTechnicalIdentityAndAddsSafeExample() {
        val manifest = OfficialSkillLibrary.manifestFor("research_agent")!!
        val localized = SkillPresentation.localized(manifest, Locale("ar"))
        assertEquals("research_agent", localized.id)
        assertEquals("research_agent", manifest.id)
        assertTrue(localized.name.any { it in '\u0600'..'\u06FF' })
        assertTrue(localized.description.any { it in '\u0600'..'\u06FF' })
        assertTrue(localized.examples.isNotEmpty())
        assertFalse(localized.examples.first().contains("Use "))
    }

    @Test
    fun englishPresentationLeavesTechnicalCatalogCopyAvailable() {
        val manifest = OfficialSkillLibrary.manifestFor("web_search")!!
        val localized = SkillPresentation.localized(manifest, Locale.ENGLISH)
        assertEquals(manifest.name, localized.name)
        assertEquals(manifest.id, localized.id)
    }
}
