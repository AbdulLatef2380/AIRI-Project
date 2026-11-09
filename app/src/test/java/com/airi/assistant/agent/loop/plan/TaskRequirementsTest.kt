package com.airi.assistant.agent.loop.plan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TaskRequirementsTest {
    private val literal = "قم بإنشاء كود لآلة حاسبة كاملة بلغة python مع ثيمات متعددة ووضع علمي وسجل للعمليات وخط عربي"

    private fun ids(text: String) = RequirementExtractor.extract(text).map { it.id }

    @Test
    fun literalRequestYieldsAllNamedRequirements() {
        assertEquals(
            listOf("language_file", "themes", "scientific_mode", "history", "arabic_font"),
            ids(literal),
        )
    }

    @Test
    fun plainChatAndQuestionsHaveNoRequirements() {
        assertTrue(RequirementExtractor.extract("مرحبا كيف حالك").isEmpty())
        assertTrue(RequirementExtractor.extract("كيف اكتب كود بايثون للثيمات").isEmpty())
    }

    @Test
    fun explicitNumberOfThemesIsHonoured() {
        val themes = RequirementExtractor.extract("اكتب كود بلغة python لآلة حاسبة مع 5 ثيمات").first { it.id == "themes" }
        assertTrue(themes.labelEn.contains("5"))
        fun themeSource(n: Int) = "THEMES = {\n" + (1..n).joinToString(",\n") { "    \"t$it\": {\"bg\": \"#00000$it\"}" } + "\n}\n"
        assertFalse(themes.evidence(mapOf("calc.py" to themeSource(4))))
        assertTrue(themes.evidence(mapOf("calc.py" to themeSource(5))))
    }

    @Test
    fun multipleThemesWithoutANumberNeedsThree() {
        val themes = RequirementExtractor.extract(literal).first { it.id == "themes" }
        assertTrue(themes.labelEn.contains("${RequirementExtractor.DEFAULT_MULTIPLE}"))
        val oneDark = "THEMES = {\"dark\": {\"bg\": \"#111111\"}}\n"
        assertFalse(themes.evidence(mapOf("calc.py" to oneDark)))
    }

    @Test
    fun languageRequirementNeedsAFileOfThatKind() {
        val language = RequirementExtractor.extract(literal).first { it.id == "language_file" }
        assertFalse(language.evidence(mapOf("notes.md" to "x")))
        assertTrue(language.evidence(mapOf("calc.py" to "print(1)")))
    }

    @Test
    fun scientificModeNeedsSeveralMathFunctions() {
        val sci = RequirementExtractor.extract(literal).first { it.id == "scientific_mode" }
        assertFalse(sci.evidence(mapOf("c.py" to "import math\nmath.sqrt(4)")))
        assertTrue(sci.evidence(mapOf("c.py" to "import math\nmath.sin(1); math.cos(1); math.sqrt(4)")))
    }

    @Test
    fun arabicFontNeedsFontSettingAndArabicText() {
        val font = RequirementExtractor.extract(literal).first { it.id == "arabic_font" }
        assertFalse(font.evidence(mapOf("c.py" to "label = 'Hello'\nfont = ('Arial', 14)")))
        assertTrue(font.evidence(mapOf("c.py" to "font = ('Arial', 14)\nA='حاسبة'\nB='سجل'\nC='مسح'")))
    }

    @Test
    fun themesInAnUnrelatedDictionaryDoNotCount() {
        assertEquals(0, RequirementExtractor.countThemeEntries("COLORS = {\"a\": {}, \"b\": {}, \"c\": {}}"))
    }
}
