package com.airi.assistant.ai

enum class QueryType { SIMPLE, ANALYTICAL, ACTION, CREATIVE, UNKNOWN }

object QueryClassifier {

    private val EXACT_GREETINGS = setOf(
        "hello", "hi", "hey", "yo", "sup", "howdy",
        "مرحبا", "أهلاً", "أهلا", "هلا", "السلام عليكم",
        "thank you", "thanks", "شكراً", "شكرا", "شكرًا",
        "ok", "okay", "alright", "fine", "got it", "cool",
        "حسناً", "تمام", "موافق", "أوكي", "bye", "goodbye"
    )

    private val CREATIVE_PATTERNS = listOf(
        "write a story", "write me a story", "write me a poem",
        "write a poem", "invent ", "brainstorm", "come up with",
        "dream up", "generate ideas", "make up a", "create a story",
        "create a poem", "fictional", "fantasy", "imagine if",
        "role play", "roleplay", "act as ", "play as ",
        "اكتب قصة", "تخيل", "اكتب قصيدة", "ابتكر",
        "فكّر في أفكار", "أفكار إبداعية", "قصة قصيرة"
    )

    private val CREATIVE_CONTENT_WORDS = listOf(
        "story", "poem", "tale", "fiction", "narrative", "novel",
        "song", "lyrics", "fairy tale", "sci-fi", "fantasy story", "adventure",
        "short story", "bedtime story", "horror story", "love story",
        "قصة", "قصيدة", "حكاية", "خيال"
    )

    private val ACTION_STARTERS = listOf(
        "send ", "write ", "implement ", "create ", "make ",
        "build ", "set up", "configure", "install ", "run ",
        "execute", "open ", "generate ", "produce ", "draft ",
        "code ", "program ", "design ", "deploy ", "fix ",
        "أرسل", "اكتب", "أنشئ", "ابنِ", "شغّل", "افتح",
        "نفّذ", "أعدّ", "اضبط", "برمج", "صمّم"
    )

    private val ANALYTICAL_PATTERNS = listOf(
        "analyze", "analyse", "compare", "explain",
        "describe in detail", "what is the difference",
        "what's the difference", "pros and cons",
        "advantages", "disadvantages", "why does",
        "why is", "how does", "how do i", "how do you",
        "summarize", "evaluate", "assess", "elaborate",
        "discuss", "step by step", "in detail",
        "حلل", "قارن", "اشرح", "الفرق بين",
        "ايجابيات", "سلبيات", "مميزات", "عيوب",
        "لماذا", "كيف يعمل", "لخّص", "ناقش", "خطوة بخطوة"
    )

    private val LIVE_DEVICE_PATTERNS = listOf(
        "what time", "current time", "time is it", "today's date",
        "كم الساعة", "الساعة الآن", "الوقت الآن", "التاريخ اليوم"
    )

    private val CONNECTOR_TARGETS = listOf(
        "github", "gitlab", "telegram", "gmail", "google calendar", "calendar",
        "slack", "discord", "notion", "repository", "repositories", "repo",
        "الموصل", "موصل", "مستودع", "مستودعات", "البريد", "التقويم", "رسائل"
    )

    private val CONNECTOR_ACTIONS = listOf(
        "list", "show", "read", "check", "find", "search", "get", "access",
        "اعرض", "اقرأ", "تحقق", "ابحث", "أحضر", "الوصول", "افتح"
    )

    /** Pure intent predicate; kept separate from Android logging for JVM tests. */
    fun requiresLiveRuntime(input: String): Boolean {
        val lower = input.trim().lowercase()
        return LIVE_DEVICE_PATTERNS.any { lower.contains(it) } ||
            (CONNECTOR_TARGETS.any { lower.contains(it) } &&
                CONNECTOR_ACTIONS.any { lower.contains(it) })
    }

    /** Compatibility adapter; classification remains non-authorizing. */
    fun classifyQuery(input: String): QueryType =
        com.airi.assistant.domain.intent.CanonicalIntentClassifier.classify(input).queryType

}
