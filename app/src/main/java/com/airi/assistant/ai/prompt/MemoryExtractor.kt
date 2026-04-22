package com.airi.assistant.ai.prompt

/**
 * Heuristic extraction of "key user facts" from user-authored messages.
 *
 * Deliberately heuristic, NOT LLM-based: invoking the LLM mid-flow to extract
 * facts would defeat the whole point of compression (extra inference work per
 * message). Coverage focuses on the highest-value patterns:
 *
 *   - identity (name, location, language)
 *   - persistent preferences ("I prefer X", "I like Y", "I hate Z")
 *   - persistent intents     ("I'm working on …", "I'm building …")
 *
 * Returns canonical "key=value" strings so MemoryStore can de-dupe by key.
 *
 * Bilingual: matches both English and a small set of common Arabic phrasings
 * since the app targets ar+en users.
 */
object MemoryExtractor {

    private val EN_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""\bmy name is ([A-Za-z][\w\s\-']{1,40})\b""", RegexOption.IGNORE_CASE)             to "name",
        Regex("""\bi(?:'m| am) ([A-Za-z][\w\s\-']{1,30})(?=[.,!?\s]|$)""", RegexOption.IGNORE_CASE) to "identity",
        Regex("""\bi live in ([A-Za-z][\w\s\-']{1,40})\b""", RegexOption.IGNORE_CASE)              to "location",
        Regex("""\bi work (?:at|for) ([A-Za-z][\w\s\-'&]{1,40})\b""", RegexOption.IGNORE_CASE)     to "employer",
        Regex("""\bi(?:'m| am) (?:building|working on) ([\w\s\-'&]{2,60})""", RegexOption.IGNORE_CASE) to "project",
        Regex("""\bi (?:like|love) ([\w\s\-'&]{2,40})""", RegexOption.IGNORE_CASE)                 to "preference",
        Regex("""\bi (?:hate|dislike) ([\w\s\-'&]{2,40})""", RegexOption.IGNORE_CASE)              to "dislike",
        Regex("""\bi prefer ([\w\s\-'&]{2,40})""", RegexOption.IGNORE_CASE)                        to "preference",
        Regex("""\bi speak ([A-Za-z]{3,20})""", RegexOption.IGNORE_CASE)                           to "language",
    )

    private val AR_PATTERNS: List<Pair<Regex, String>> = listOf(
        Regex("""اسمي\s+([\u0600-\u06FF\w\s]{2,40})""")                  to "name",
        Regex("""أعيش\s+في\s+([\u0600-\u06FF\w\s]{2,40})""")              to "location",
        Regex("""أعمل\s+في\s+([\u0600-\u06FF\w\s]{2,40})""")              to "employer",
        Regex("""أحب\s+([\u0600-\u06FF\w\s]{2,40})""")                    to "preference",
        Regex("""أكره\s+([\u0600-\u06FF\w\s]{2,40})""")                   to "dislike",
        Regex("""أتحدث\s+(?:ال)?([\u0600-\u06FF\w\s]{2,30})""")           to "language",
    )

    /**
     * Extract facts from a single user message. Returns a list of "key=value"
     * strings ready to feed into [MemoryStore.mergeFacts]. Quietly returns an
     * empty list when nothing matches — extraction is best-effort.
     */
    fun extract(userMessage: String): List<String> {
        if (userMessage.isBlank() || userMessage.length > 4_000) return emptyList()
        val out = mutableListOf<String>()
        for ((regex, key) in EN_PATTERNS) {
            regex.find(userMessage)?.groupValues?.getOrNull(1)?.let { v ->
                val cleaned = v.trim().trimEnd('.', ',', ';', '!', '?').take(60)
                if (cleaned.isNotBlank()) out.add("$key=$cleaned")
            }
        }
        for ((regex, key) in AR_PATTERNS) {
            regex.find(userMessage)?.groupValues?.getOrNull(1)?.let { v ->
                val cleaned = v.trim().trimEnd('.', ',', ';', '!', '?', '،').take(60)
                if (cleaned.isNotBlank()) out.add("$key=$cleaned")
            }
        }
        return out.distinct()
    }
}
