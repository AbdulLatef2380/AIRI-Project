package com.airi.assistant.agent.loop.plan

import com.airi.assistant.ai.CapabilityIntentDetector
import com.airi.assistant.ai.CodingTaskDetector

/**
 * One checkable requirement taken from the user's request ("three themes", "a scientific mode",
 * "an Arabic font"). The plan phase records the list; the verify phase asks for evidence in the
 * source files the agent actually saved.
 *
 * The evidence tests are structural heuristics over source text (they look for the shapes such
 * code normally has). They are not a substitute for running the program, and a requirement that
 * fails here produces a correction turn, never a claim that the program is wrong.
 */
data class TaskRequirement(
    val id: String,
    val labelAr: String,
    val labelEn: String,
    /** True when the saved sources (file name -> content) show the requirement is met. */
    val evidence: (Map<String, String>) -> Boolean,
) {
    fun label(arabic: Boolean): String = if (arabic) labelAr else labelEn
}

object RequirementExtractor {

    /** Default count for "multiple themes" style wording that names no number. */
    const val DEFAULT_MULTIPLE = 3

    private val digitsOnly = Regex("\\d+")

    private fun terms(vararg values: String): List<String> =
        values.map { CapabilityIntentDetector.normalize(it) }.filter { it.isNotEmpty() }

    private val themeWords = terms("ثيمات", "ثيم", "سمات", "themes", "theme")
    private val scientificWords = terms("علمي", "علمية", "الة حاسبة علمية", "scientific")
    private val historyWords = terms("سجل", "history")
    private val colorWords = terms("الوان", "ألوان", "colors", "colours")
    private val arabicFontWords = terms("خط عربي", "الخط العربي", "خطوط عربية", "arabic font")

    private val languageExtensions = mapOf(
        "python" to ".py", "kotlin" to ".kt", "java" to ".java", "javascript" to ".js",
        "typescript" to ".ts", "html" to ".html", "css" to ".css", "sql" to ".sql",
        "bash" to ".sh", "php" to ".php", "ruby" to ".rb", "lua" to ".lua", "dart" to ".dart",
        "swift" to ".swift", "rust" to ".rs", "golang" to ".go", "c++" to ".cpp", "cpp" to ".cpp",
        "c#" to ".cs",
    )

    /** Requirements for a coding request; empty for text that is not a coding task. */
    fun extract(userText: String): List<TaskRequirement> {
        val match = CodingTaskDetector.detect(userText) ?: return emptyList()
        val text = CapabilityIntentDetector.normalize(userText)
        val out = mutableListOf<TaskRequirement>()

        // 1. The requested language must show up as a saved file of that kind.
        match.languages.firstNotNullOfOrNull { languageExtensions[it] }?.let { ext ->
            val language = match.languages.first { languageExtensions.containsKey(it) }
            out += TaskRequirement(
                id = "language_file",
                labelAr = "ملف بلغة $language (*$ext)",
                labelEn = "A $language source file (*$ext)",
            ) { files -> files.keys.any { it.lowercase().endsWith(ext) } }
        }

        // 2. Themes: "3 ثيمات" asks for three; "ثيمات متعددة" asks for the default of three.
        if (themeWords.any { it in text }) {
            val count = numberBefore(text, themeWords) ?: DEFAULT_MULTIPLE
            out += TaskRequirement(
                id = "themes",
                labelAr = "$count ثيمات على الأقل",
                labelEn = "At least $count themes",
            ) { files -> files.values.any { countThemeEntries(it) >= count } }
        }

        // 3. Scientific mode.
        if (scientificWords.any { it in text }) {
            out += TaskRequirement(
                id = "scientific_mode",
                labelAr = "وضع علمي (دوال رياضية)",
                labelEn = "A scientific mode (math functions)",
            ) { files -> files.values.any { distinctMathFunctions(it) >= 3 } }
        }

        // 4. History.
        if (historyWords.any { it in text }) {
            out += TaskRequirement(
                id = "history",
                labelAr = "سجل للعمليات",
                labelEn = "A history of operations",
            ) { files -> files.values.any { HISTORY_REGEX.containsMatchIn(it) } }
        }

        // 5. Colors.
        if (colorWords.any { it in text }) {
            out += TaskRequirement(
                id = "colors",
                labelAr = "ألوان محددة (3 على الأقل)",
                labelEn = "Defined colors (at least 3)",
            ) { files -> files.values.any { HEX_COLOR_REGEX.findAll(it).map { m -> m.value.lowercase() }.toSet().size >= 3 } }
        }

        // 6. Arabic font / Arabic text.
        if (arabicFontWords.any { it in text }) {
            out += TaskRequirement(
                id = "arabic_font",
                labelAr = "خط عربي ونصوص عربية في الواجهة",
                labelEn = "An Arabic font and Arabic UI text",
            ) { files ->
                files.values.any { FONT_REGEX.containsMatchIn(it) && ARABIC_TEXT_REGEX.findAll(it).count() >= 3 }
            }
        }
        return out
    }

    /** A number written directly before one of [words] ("3 ثيمات", "three"-style words are not parsed). */
    private fun numberBefore(text: String, words: List<String>): Int? {
        for (word in words) {
            val index = text.indexOf(word)
            if (index <= 0) continue
            val before = text.substring(0, index).trimEnd()
            val last = before.substringAfterLast(' ')
            if (digitsOnly.matches(last)) return last.toIntOrNull()?.coerceIn(1, 12)
        }
        return null
    }

    /**
     * Distinct entries of a themes table: quoted keys that open a nested dict (`"dark": {`), only
     * counted in sources that mention themes at all so unrelated dictionaries do not count.
     */
    internal fun countThemeEntries(source: String): Int {
        if (!THEME_WORD_REGEX.containsMatchIn(source)) return 0
        val start = THEME_WORD_REGEX.find(source)!!.range.first
        return THEME_ENTRY_REGEX.findAll(source, start).map { it.groupValues[1].lowercase() }.toSet().size
    }

    internal fun distinctMathFunctions(source: String): Int =
        MATH_FUNCTION_REGEX.findAll(source).map { it.value.lowercase() }.toSet().size

    private val THEME_WORD_REGEX = Regex("(?i)themes?")
    private val THEME_ENTRY_REGEX = Regex("[\"']([^\"'\\n]{2,32})[\"']\\s*:\\s*\\{")
    private val MATH_FUNCTION_REGEX = Regex("(?i)\\b(sin|cos|tan|asin|acos|atan|sqrt|log|log10|ln|exp|factorial|pow|abs)\\b")
    private val HISTORY_REGEX = Regex("(?i)history")
    private val HEX_COLOR_REGEX = Regex("#[0-9a-fA-F]{6}\\b")
    private val FONT_REGEX = Regex("(?i)font")
    private val ARABIC_TEXT_REGEX = Regex("[\\u0600-\\u06FF]{2,}")
}
