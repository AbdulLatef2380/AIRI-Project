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
        DEVICE_STATE,
        DEVICE_ACTION,
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

    private val timePatterns = listOf(
        "what time", "current time", "time is it", "today's date", "todays date",
        "current date", "date today", "what date", "today",
        "كم الساعة", "الساعة الان", "الوقت الان", "التاريخ اليوم",
        "تاريخ اليوم", "التاريخ الحالي", "التاريخ الميلادي", "اليوم كم",
        "كم اليوم", "ما تاريخ اليوم", "ما هو تاريخ اليوم", "تاريخ اليوم كم",
    )

    private val memoryPatterns = listOf(
        "remember", "recall", "memory", "what did i tell you", "what do you remember",
        "do you remember", "my memories", "previously",
        "تذكر", "تذكّر", "ذاكرتك", "ذاكرتي", "ماذا تتذكر", "ماذا تذكر عني",
        "هل تتذكر", "ما الذي اخبرتك", "ما الذي أخبرتك", "المعلومات التي حفظتها",
    )

    private val webPatterns = listOf(
        "search the web", "search online", "look up", "latest news", "current price",
        "ابحث في الويب", "ابحث على الانترنت", "ابحث على الإنترنت", "آخر الأخبار",
        "اخر الاخبار", "السعر الحالي", "معلومات حديثة", "معلومة حديثة",
    )

    private val connectorTargets = mapOf(
        "gmail" to "google", "جيميل" to "google", "البريد" to "google",
        "email" to "google", "google calendar" to "google", "calendar" to "google",
        "التقويم" to "google", "github" to "github", "جيت هب" to "github",
        "telegram" to "telegram", "تلغرام" to "telegram", "slack" to "slack",
        "discord" to "discord", "notion" to "notion", "الموصلات" to "*",
    )

    private val connectorActions = listOf(
        "list", "show", "read", "check", "find", "search", "get", "access", "summarize",
        "اعرض", "عرض", "اقرأ", "اقرا", "تحقق", "ابحث", "أحضر", "احضر", "الوصول",
        "افتح", "لخص", "لخّص", "استخرج", "راجع", "آخر", "اخر",
    )

    private val deviceActionVerbs = listOf(
        "turn on", "turn off", "switch on", "switch off", "enable", "disable", "toggle",
        "activate", "deactivate", "قم بتشغيل", "قم بايقاف", "قم بإيقاف", "شغل", "شغّل",
        "تشغيل", "فعل", "فعّل", "اطفئ", "أطفئ", "اطفاء", "إطفاء", "إيقاف", "ايقاف",
    )

    private val deviceActionTargets = listOf(
        "flash", "flashlight", "torch", "wifi", "wi-fi", "bluetooth", "hotspot",
        "airplane mode", "mobile data", "location", "الفلاش", "فلاش", "الكشاف",
        "المصباح", "الواي فاي", "واي فاي", "البلوتوث", "نقطة الاتصال", "وضع الطيران",
        "بيانات الهاتف", "الموقع",
    )

    fun detect(input: String): Intent {
        val text = normalize(input)
        val capabilities = linkedSetOf<Capability>()
        val connectors = linkedSetOf<String>()

        if (timePatterns.any(text::contains)) capabilities += Capability.CURRENT_TIME
        if (memoryPatterns.any(text::contains)) capabilities += Capability.MEMORY_READ
        if (webPatterns.any(text::contains)) capabilities += Capability.WEB_SEARCH

        val target = connectorTargets.entries.firstOrNull { text.contains(it.key) }
        if (target != null && connectorActions.any(text::contains)) {
            capabilities += Capability.CONNECTOR_READ
            if (target.value != "*") connectors += target.value
        }

        if (text.contains("battery") || text.contains("network") ||
            text.contains("البطارية") || text.contains("الشبكة")) {
            capabilities += Capability.DEVICE_STATE
        }
        if (deviceActionVerbs.any(text::contains) && deviceActionTargets.any(text::contains)) {
            capabilities += Capability.DEVICE_ACTION
        }
        return Intent(capabilities, connectors)
    }

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
}
