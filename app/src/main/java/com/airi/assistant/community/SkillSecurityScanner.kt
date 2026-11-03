package com.airi.assistant.community

/**
 * Single home for the static manifest scan used by both the community hub and the
 * marketplace install path (Report 3, M1: the marketplace never called the scan).
 */
object SkillSecurityScanner {

    /** Patterns that fail a skill definition outright. */
    private val DANGEROUS_PATTERNS = listOf(
        Regex("""exec\s*\("""),
        Regex("""eval\s*\("""),
        Regex("""Runtime\.getRuntime"""),
        Regex("""ProcessBuilder"""),
        Regex("""file:///"""),
        Regex("""content://"""),
        Regex("""http://"""),  // non-HTTPS
        Regex("""password|secret|private_key""", RegexOption.IGNORE_CASE)
    )

    /** Returns the patterns that matched; an empty list means the scan passed. */
    fun scan(jsonString: String): List<String> =
        DANGEROUS_PATTERNS.filter { it.containsMatchIn(jsonString) }.map { it.pattern }
}
