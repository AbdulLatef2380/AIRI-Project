package com.airi.assistant.ai

/**
 * Detects "build me a program" requests ("قم بإنشاء كود لآلة حاسبة ... بلغة python",
 * "write a Python script that ...") so they reach the agent path with file tools
 * instead of falling into the tool-free chat/creative path.
 *
 * Pure and conservative: a request is a coding task only when it has
 *  - a creation verb ("انشئ", "اكتب", "صمم", "برمج", "create", "build" ...), and
 *  - a coding object (كود/برنامج/تطبيق/سكربت, code/script/app ...) or a programming
 *    language named as the implementation language ("بلغة python", "in kotlin"), and
 *  - it does not open as a question or an explanation request ("كيف اكتب كود ..."),
 *    which stays an ordinary chat answer.
 *
 * Matching is token based (not substring) so that Arabic clitics ("لكود", "بإنشاء",
 * "والبرنامج") match while "ابني"/"ولد" (my son / a boy) can never act as verbs.
 * The detector classifies intent only; it never grants tool permission.
 */
object CodingTaskDetector {
    data class Match(
        /** Normalized languages named in the request, in order of appearance (may be empty). */
        val languages: List<String>,
        val verb: String,
        /** The coding object that matched, or the language when no noun was present. */
        val target: String,
    )

    private fun terms(vararg values: String): Set<String> =
        values.map { CapabilityIntentDetector.normalize(it) }.filter { it.isNotEmpty() }.toSet()

    private val creationVerbs = terms(
        "انشاء", "انشئ", "انشي", "انشىء", "اصنع", "صنع", "صمم", "تصميم", "برمج", "برمجة",
        "اعمل", "عمل", "اكتب", "كتابة", "بناء", "طور", "تطوير", "جهز",
        "create", "build", "make", "write", "develop", "generate", "design", "implement",
    )

    private val codingNouns = terms(
        "كود", "اكواد", "كودا", "برنامج", "برامج", "تطبيق", "تطبيقات", "سكربت", "سكريبت",
        "سكربتات", "شفرة", "شفره",
        "code", "script", "program", "app", "application", "function", "class", "module", "bot",
        "codes", "scripts", "programs", "apps",
    )

    private val languages = terms(
        "python", "بايثون", "بايثن", "kotlin", "كوتلن", "java", "جافا", "javascript", "جافاسكربت",
        "جافاسكريبت", "typescript", "c++", "c#", "cpp", "rust", "swift", "php", "ruby", "lua",
        "dart", "flutter", "kivy", "tkinter", "html", "css", "sql", "bash", "golang",
    )

    /** Words that mark the next token as the implementation language, not the topic. */
    private val languageCues = terms(
        "بلغة", "بلغه", "باستخدام", "بواسطة", "لغة", "لغه", "البرمجة", "using", "in", "with",
        "language", "programming",
    )

    /** A first word that makes the request a question or an explanation, not a build order. */
    private val nonBuildOpeners = terms(
        "ما", "ماذا", "لماذا", "كيف", "من", "اشرح", "شرح", "حلل", "قارن", "لخص", "راجع",
        "what", "why", "how", "who", "explain", "analyze", "analyse", "compare", "summarize",
        "review", "describe",
    )

    private val arabicPrefixes = listOf("ال", "و", "ف", "ب", "ل", "ك")
    private val tokenRegex = Regex("[\\p{L}\\p{N}+#]+")

    /** The token plus the forms left after stripping up to two Arabic clitic prefixes. */
    private fun variants(token: String): Set<String> {
        val out = linkedSetOf(token)
        var frontier = listOf(token)
        repeat(2) {
            val next = mutableListOf<String>()
            frontier.forEach { current ->
                arabicPrefixes.forEach { prefix ->
                    if (current.startsWith(prefix) && current.length - prefix.length >= 2) {
                        val stripped = current.removePrefix(prefix)
                        if (out.add(stripped)) next += stripped
                    }
                }
            }
            frontier = next
        }
        if (token.length > 3 && token.endsWith("s") && token.all { it.code < 128 }) out += token.dropLast(1)
        return out
    }

    /** The coding object must follow the verb closely ("اكتب لي كود", "create a python script"). */
    private const val MAX_VERB_TO_TARGET_DISTANCE = 4

    fun detect(input: String): Match? {
        val tokens = tokenRegex.findAll(CapabilityIntentDetector.normalize(input)).map { it.value }.toList()
        if (tokens.size < 2) return null
        val tokenVariants = tokens.map(::variants)
        if (tokenVariants.first().any { it in nonBuildOpeners }) return null

        val named = tokenVariants.indices.mapNotNull { index ->
            tokenVariants[index].firstOrNull { it in languages }?.let { index to it }
        }
        val languageNames = named.map { it.second }.distinct()

        for (verbIndex in tokenVariants.indices) {
            val verb = tokenVariants[verbIndex].firstOrNull { it in creationVerbs } ?: continue
            val window = (verbIndex + 1)..minOf(tokens.lastIndex, verbIndex + MAX_VERB_TO_TARGET_DISTANCE)
            val noun = window.firstNotNullOfOrNull { index ->
                tokenVariants[index].firstOrNull { it in codingNouns }
            }
            // A bare language word is only the implementation language after a cue ("بلغة python"),
            // so "اكتب لي عن python" or "write about java" is not a build order.
            val cuedLanguage = named.firstOrNull { (index, _) ->
                index > verbIndex && (
                    tokenVariants[index - 1].any { it in languageCues } ||
                        (index > 1 && tokenVariants[index - 2].any { it in languageCues })
                    )
            }?.second
            val target = noun ?: cuedLanguage ?: continue
            return Match(languages = languageNames, verb = verb, target = target)
        }
        return null
    }

    fun isCodingTask(input: String): Boolean = detect(input) != null
}
