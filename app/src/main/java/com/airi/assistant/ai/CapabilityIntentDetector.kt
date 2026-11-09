package com.airi.assistant.ai

/**
 * Detects runtime data dependencies independently from [QueryType].
 *
 * QueryType describes the shape of an answer; this object describes which
 * live capability is required to produce a truthful answer. It is deliberately
 * pure and conservative so it can be tested on the JVM and used before the
 * agent/tool loop is selected.
 */
object CapabilityIntentDetector {
    enum class Capability {
        CURRENT_TIME,
        MEMORY_READ,
        WEB_SEARCH,
        CONNECTOR_READ,
        CONNECTOR_WRITE,
        DEVICE_STATE,
        DEVICE_ACTION,
        TERMINAL_EXECUTE,
        FILE_WRITE,
        /** "Build me a program": needs write_file so the result is delivered as a file. */
        CODE_TASK,
    }

    data class Intent(
        val capabilities: Set<Capability> = emptySet(),
        val connectorIds: Set<String> = emptySet(),
    ) {
        val requiresTools: Boolean get() = capabilities.isNotEmpty()
        val requiresLiveData: Boolean
            get() = capabilities.any { it != Capability.MEMORY_READ }
        fun requires(capability: Capability): Boolean = capability in capabilities
    }

    private val timePatterns = normalizedTerms(
        "what time", "current time", "time is it", "today's date", "todays date",
        "current date", "date today", "what date",
        "كم الساعة", "الساعة الان", "الوقت الان", "التاريخ اليوم",
        "تاريخ اليوم", "التاريخ الحالي", "التاريخ الميلادي", "اليوم كم",
        "كم اليوم", "ما تاريخ اليوم", "ما هو تاريخ اليوم", "تاريخ اليوم كم",
    )

    private val memoryPatterns = normalizedTerms(
        "remember", "recall", "memory", "what did i tell you", "what do you remember",
        "do you remember", "my memories", "previously",
        "تذكر", "تذكّر", "ذاكرتك", "ذاكرتي", "ماذا تتذكر", "ماذا تذكر عني",
        "هل تتذكر", "ما الذي اخبرتك", "ما الذي أخبرتك", "المعلومات التي حفظتها",
    )

    private val webPatterns = normalizedTerms(
        "search the web", "search online", "look up", "latest news", "current price",
        "ابحث في الويب", "ابحث على الانترنت", "ابحث على الإنترنت", "آخر الأخبار",
        "اخر الاخبار", "الاخبار", "اخبار", "خبر", "السعر الحالي", "سعر الدولار",
        "سعر الصرف", "معلومات حديثة", "معلومة حديثة", "احدث اصدار", "أحدث إصدار",
        "افتح هذا الرابط", "لخص محتواه", "لخص الرابط", "قارن أحدث المعلومات",
    )

    private val connectorTargets = mapOf(
        "gmail" to "google", "جيميل" to "google", "البريد" to "google",
        "email" to "google", "google drive" to "google", "drive" to "google",
        "جوجل درايف" to "google", "درايف" to "google", "google calendar" to "google", "calendar" to "google",
        "التقويم" to "google", "github" to "github", "جيت هب" to "github",
        "telegram" to "telegram", "تلغرام" to "telegram", "slack" to "slack",
        "discord" to "discord", "notion" to "notion", "الموصلات" to "*",
    )

    /**
     * Report 1, item 7 (stage 30): personal-data nouns that imply a connector without
     * naming it ("بريدي", "my repos"). These widen discovery for READ only: a write
     * still requires the connector to be named explicitly, and the connector must be
     * connected and healthy for its tools to be exposed at all.
     */
    private val impliedReadTargets: Map<String, String> = mapOf(
        "my emails" to "google", "my email" to "google", "my inbox" to "google",
        "my calendar events" to "google", "my meetings" to "google", "my schedule" to "google",
        "بريدي" to "google", "ايميلاتي" to "google", "رسائل بريدي" to "google",
        "صندوق الوارد" to "google", "مواعيدي" to "google", "اجتماعاتي" to "google",
        "my repos" to "github", "my repositories" to "github", "my pull requests" to "github",
        "مستودعاتي" to "github", "طلبات الدمج" to "github",
    ).mapKeys { normalize(it.key) }

    // Report 1, item 5 (stage 33): asking for a file to be produced ("اكتب ملف", "save it as a
    // file"). This only offers the write_file tool; the tool itself still needs a task-owned
    // context, governance and a sanitized name.
    private val fileWritePhrases = normalizedTerms(
        "write a file", "create a file", "make a file", "save to a file", "save it to a file",
        "save it as a file",
        "save as a file", "save this as a file", "write it to a file", "generate a file",
        "اكتب ملف", "اكتب ملفا", "انشئ ملف", "انشئ ملفا", "سو ملف", "احفظ في ملف", "احفظه في ملف",
        "احفظ كملف", "احفظه كملف", "احفظ الكود في ملف", "اعطني ملف", "ولد ملف",
    )

    private val connectorReadActions = normalizedTerms(
        "list", "show", "read", "check", "find", "search", "get", "access", "summarize",
        "اعرض", "عرض", "اقرأ", "اقرا", "تحقق", "ابحث", "أحضر", "احضر", "الوصول",
        "افتح", "لخص", "لخّص", "استخرج", "راجع", "حلل", "حلّل", "تحليل", "آخر", "اخر",
        "my", "mine",
        "لدي", "لديك", "حساب", "ملفاتي", "رسائلي", "مستودعاتي", "المستودعات",
    )

    private val connectorWriteActions = normalizedTerms(
        "send", "create", "update", "delete", "write", "post", "reply", "upload", "commit",
        "edit", "modify", "rename", "move", "clear",
        "ارسل", "انشئ", "اكتب", "حدث", "احذف", "انشر", "ارفع", "رد", "عدل", "غير",
        "تحديث", "تعديل", "حذف",
    )

    private val githubRepositoryTerms = normalizedTerms(
        "repository", "repositories", "repo", "github.com", "المستودع", "المستودعات",
        "المشروع", "مشروع github", "رابط github"
    )

    /** Capability-discovery questions must request the complete connector surface. */
    private val capabilityDiscoveryPatterns = normalizedTerms(
        "what tools", "available tools", "what skills", "available skills", "what connectors",
        "how many connectors", "how many tools", "how many skills", "what is enabled", "what is currently enabled",
        "ما هي الادوات", "ما هي الأدوات", "الأدوات المتاحة", "الادوات المتاحة",
        "ما هي المهارات", "المهارات المتاحة", "ما هي الموصلات", "الموصلات المتاحة",
        "كم موصل", "كم من الموصلات", "عدد الموصلات", "كم اداة", "كم أداة", "عدد الأدوات",
        "كم مهارة", "عدد المهارات", "ما الذي تم تفعيله", "ما الذي يعمل حاليا", "ما الذي يعمل الآن",
        "ما هي الخدمات الخارجية", "الخدمات الخارجية المتاحة",
        "ما الذي تستطيع", "ماذا تستطيع", "بماذا يمكنك مساعدتي", "ما قدراتك",
        "ما الذي يمكنك فعله", "ما يمكنك فعله", "اعطني قائمة الادوات", "أعطني قائمة الأدوات",
    )

    // Do not require one exact phrase for a harmless inventory question. Small
    // models and Arabic users vary word order and use colloquial wording.
    private val capabilityInventoryNouns = normalizedTerms(
        "tool", "tools", "skill", "skills", "connector", "connectors", "capability",
        "اداة", "ادوات", "مهارة", "مهارات", "موصل", "موصلات", "قدرات",
        "خدمات", "خدمة", "integrations", "تكاملات", "الموصلات الخارجية",
    )

    private val capabilityInventoryQuestionWords = normalizedTerms(
        "what", "which", "how many", "list", "show", "available", "enabled",
        "ما", "ماذا", "اي", "أي", "كم", "عدد", "اذكر", "اعرض", "اعطني",
    )

    private val deviceActionVerbs = normalizedTerms(
        "turn on", "turn off", "switch on", "switch off", "enable", "disable", "toggle",
        "activate", "deactivate", "قم بتشغيل", "قم بايقاف", "قم بإيقاف", "شغل", "شغّل",
        "تشغيل", "فعل", "فعّل", "اطفئ", "أطفئ", "اطفاء", "إطفاء", "إيقاف", "ايقاف",
    )

    private val deviceActionTargets = normalizedTerms(
        "flash", "flashlight", "torch", "wifi", "wi-fi", "bluetooth", "hotspot",
        "airplane mode", "mobile data", "location", "الفلاش", "فلاش", "الكشاف",
        "المصباح", "الواي فاي", "واي فاي", "البلوتوث", "نقطة الاتصال", "وضع الطيران",
        "بيانات الهاتف", "الموقع",
    )

    private val terminalTargets = normalizedTerms(
        "terminal", "shell", "sandbox", "command line",
        "الطرفية", "طرفية", "سطر الاوامر", "سطر الأوامر", "بيئة الاختبار",
    )

    private val terminalActionPatterns = normalizedTerms(
        "run", "execute", "list", "show", "read", "print", "pwd", "ls", "cat",
        "نفذ", "شغل", "اعرض", "اقرا", "اقرأ", "اطبع", "اعرض الملفات",
        "نفذ امرا", "نفذ أمرا", "شغل امرا", "شغل أمرا",
    )

    // Report 1, item 8 (stage 24): a command can be asked for without naming the
    // terminal ("run `ls -la`", "نفذ الأمر pwd"). These three lists describe that
    // shape; execution stays bounded by TerminalExecutionPolicy and its approvals,
    // so widening detection only decides whether the tool is offered to the model.
    private val terminalExecuteVerbs = normalizedTerms(
        "run", "execute", "نفذ", "شغل", "شغّل",
    )

    private val terminalCommandNouns = normalizedTerms(
        "command", "shell command", "bash command", "الامر", "الأمر", "امر", "أمر",
    )

    private val terminalCommandWords = normalizedTerms(
        "ls", "pwd", "grep", "git status", "git log", "git diff",
    )

    private val inlineCodeCommand = Regex("`([^`\\n]{1,200})`")

    private fun hasInlineTerminalCommand(input: String): Boolean =
        inlineCodeCommand.findAll(input).any { match ->
            val first = match.groupValues[1].trim().split(Regex("\\s+")).firstOrNull()?.lowercase().orEmpty()
            first in setOf("ls", "pwd", "grep", "cat", "echo", "head", "tail", "wc", "git")
        }

    /**
     * The input goes through [normalize] (hamza/alef/ya/diacritics folded), so
     * the phrase lists must go through it too. Raw lists such as "إيقاف",
     * "ابحث على الإنترنت" or "آخر الأخبار" never matched a normalized input.
     * Report 1, stage 13.
     */
    private fun normalizedTerms(vararg terms: String): List<String> =
        terms.map { normalize(it) }.filter { it.isNotEmpty() }.distinct()

    /**
     * Phrase match used for device and terminal intent. Terms of four letters
     * or fewer ("ls", "cat", "run", "flash", Arabic "فعل") must stand alone;
     * a plain substring test made "cat" match "locate" and "فعل" match
     * "يفعل". Longer phrases keep the substring behaviour so Arabic clitics
     * (والبطارية) still match. Report 1, stage 14.
     */
    private fun matchesAny(text: String, terms: List<String>): Boolean =
        terms.any { term -> if (term.length <= 4) containsTerm(text, term) else text.contains(term) }

    fun detect(input: String): Intent {
        val text = normalize(input)
        val capabilities = linkedSetOf<Capability>()
        val connectors = linkedSetOf<String>()

        if (timePatterns.any(text::contains) || text == "today" || text == "اليوم") {
            capabilities += Capability.CURRENT_TIME
        }
        if (memoryPatterns.any(text::contains)) capabilities += Capability.MEMORY_READ
        val hasCurrencyPairRequest = text.contains("سعر") &&
            (text.contains("دولار") || text.contains("جنيه") || text.contains("عملة") || text.contains("صرف"))
        val hasRecentNewsRequest = (text.contains("خبر") || text.contains("اخبار") || text.contains("الاخبار")) &&
            (text.contains("اخر") || text.contains("حديث") || text.contains("اليوم") || text.contains("24 ساع"))
        val hasExplicitWebRequest = webPatterns.any(text::contains) ||
            text.contains("http://") || text.contains("https://")
        if (hasExplicitWebRequest || hasCurrencyPairRequest || hasRecentNewsRequest) {
            capabilities += Capability.WEB_SEARCH
        }

        // A request may mention more than one provider (for example Gmail and
        // GitHub in the same sentence). Selecting only the first match silently
        // drops the remaining capability and makes the agent appear unable to
        // reach an otherwise connected connector. Collect all matching targets
        // and deduplicate aliases that map to the same runtime connector.
        val matchedConnectorIds = connectorTargets.entries
            .filter { containsTerm(text, it.key) }
            .map { it.value }
            .toCollection(LinkedHashSet())
        val githubUrlIntent = text.contains("github.com/") &&
            Regex("github\\.com/[a-z0-9_.-]+/[a-z0-9_.-]+", RegexOption.IGNORE_CASE)
                .containsMatchIn(text)
        val githubRepositoryIntent = githubRepositoryTerms.any(text::contains) &&
            (text.contains("github") || text.contains("جيت هب"))
        val capabilityDiscovery = capabilityDiscoveryPatterns.any { containsTerm(text, it) } ||
            isLooseCapabilityInventoryQuestion(text)
        // Ownership wording is an explicit read intent too: "my GitHub repos"
        // and "مستودعاتي في GitHub" previously fell through to a hallucinated
        // answer because they did not contain one of the narrow action verbs.
        val ownershipRead = listOf("my", "mine", "لدي", "لديك", "حساب", "ملفاتي", "رسائلي", "مستودعاتي")
            .any { containsTerm(text, it) }
        val connectorRead = connectorReadActions.any { containsTerm(text, it) } || ownershipRead ||
            (matchedConnectorIds.contains("github") && (githubUrlIntent || githubRepositoryIntent))
        val connectorWrite = !capabilityDiscovery && connectorWriteActions.any { containsTerm(text, it) }
        if (matchedConnectorIds.isNotEmpty() && (connectorRead || connectorWrite ||
                (matchedConnectorIds.contains("github") && (githubUrlIntent || githubRepositoryIntent)))) {
            if (connectorRead) capabilities += Capability.CONNECTOR_READ
            if (connectorWrite) capabilities += Capability.CONNECTOR_WRITE
            // `*` explicitly requests every connector. An empty set means
            // "no connector requested" to RuntimeToolCatalog, so it must not be
            // discarded here.
            connectors += matchedConnectorIds
        }

        if (matchedConnectorIds.isEmpty() && !capabilityDiscovery) {
            val implied = impliedReadTargets.entries
                .filter { text.contains(it.key) }
                .map { it.value }
            if (implied.isNotEmpty()) {
                capabilities += Capability.CONNECTOR_READ
                connectors += implied
            }
        }

        if (capabilityDiscovery) {
            capabilities += Capability.CONNECTOR_READ
            connectors += "*"
        }

        if (text.contains("battery") || text.contains("network") ||
            text.contains("البطارية") || text.contains("الشبكة")) {
            capabilities += Capability.DEVICE_STATE
        }
        if (matchesAny(text, deviceActionVerbs) && matchesAny(text, deviceActionTargets)) {
            capabilities += Capability.DEVICE_ACTION
        }
        val namesTerminal = matchesAny(text, terminalTargets) && matchesAny(text, terminalActionPatterns)
        val asksToRunCommand = matchesAny(text, terminalExecuteVerbs) && (
            matchesAny(text, terminalCommandNouns) ||
                matchesAny(text, terminalCommandWords) ||
                hasInlineTerminalCommand(input)
            )
        if (namesTerminal || asksToRunCommand) {
            capabilities += Capability.TERMINAL_EXECUTE
        }
        if (fileWritePhrases.any { text.contains(it) }) capabilities += Capability.FILE_WRITE
        if (CodingTaskDetector.isCodingTask(text)) capabilities += Capability.CODE_TASK
        return Intent(capabilities, connectors)
    }

    /** True when the user asks for a capability inventory, not an action. */
    fun isCapabilityDiscovery(input: String): Boolean =
        capabilityDiscoveryPatterns.any(normalize(input)::contains) ||
            isLooseCapabilityInventoryQuestion(normalize(input))

    /** Normalizes Arabic orthography and diacritics before matching phrases. */
    fun normalize(input: String): String = input
        .lowercase()
        .replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        .replace('أ', 'ا')
        .replace('إ', 'ا')
        .replace('آ', 'ا')
        .replace('ى', 'ي')
        .replace(Regex("\\s+"), " ")
        .trim()

    private fun containsTerm(text: String, term: String): Boolean {
        val escaped = Regex.escape(normalize(term))
        return Regex("(^|[^\\p{L}\\p{N}])$escaped($|[^\\p{L}\\p{N}])")
            .containsMatchIn(text)
    }

    private fun isLooseCapabilityInventoryQuestion(normalizedText: String): Boolean {
        val hasQuestionWord = capabilityInventoryQuestionWords.any { containsTerm(normalizedText, it) }
        val nounCount = capabilityInventoryNouns.count { containsTerm(normalizedText, it) }
        return hasQuestionWord && nounCount > 0
    }
}
