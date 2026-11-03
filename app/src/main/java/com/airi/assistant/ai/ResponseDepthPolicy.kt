package com.airi.assistant.ai

/**
 * Pure response-shape policy. QueryType describes routing and authorization;
 * it must not force every read-only question into a two-sentence answer.
 */
enum class ResponseDepth {
    BRIEF,
    STANDARD,
    DETAILED,
}

object ResponseDepthPolicy {
    private val detailMarkers = listOf(
        "explain", "describe", "why", "how", "compare", "difference",
        "pros and cons", "step by step", "in detail", "elaborate",
        "اشرح", "وضح", "وضّح", "لماذا", "كيف", "قارن", "الفرق",
        "خطوة بخطوة", "بالتفصيل", "حلل", "حلّل", "شرح شامل", "تحليل شامل",
        "تقرير مفصل", "تقرير شامل", "بأكبر قدر من التفاصيل",
    )

    /**
     * Requests whose deliverable is code or a document are long by nature. A
     * 1,024-token ceiling cut a typical program at ~80 lines (report 1, item 12).
     */
    private val deliverableMarkers = listOf(
        "write code", "write a program", "write a script", "write a function",
        "source code", "full code", "complete code",
        "اكتب كود", "اكتب لي كود", "اكتب برنامج", "اكتب لي برنامج", "اكتب سكربت",
        "اكتب دالة", "اكتب تطبيق", "برنامج كامل", "كود كامل", "الكود كاملا", "الكود كاملاً",
        "تطبيق كامل", "سكربت كامل", "صمم لي", "أنشئ لي برنامج", "انشئ لي برنامج",
    )

    private val briefMarkers = listOf(
        "briefly", "in one sentence", "short answer", "be concise", "just the answer",
        "باختصار", "باختصار شديد", "إجابة قصيرة", "اجب باختصار", "أجب باختصار",
        "في جملة واحدة", "بدون شرح طويل",
    )

    private val openEndedMarkers = listOf(
        "what can you do", "what are your capabilities", "tell me about",
        "give me an overview", "walk me through", "ما الذي تستطيع",
        "ماذا تستطيع", "ما هي قدراتك", "حدثني عن", "أعطني نظرة عامة",
        "what do you know about", "ماذا تعرف عن", "ما تعرفه عن", "ما رأيك",
    )

    /**
     * A question is never "brief" merely because it is short: "what do you know
     * about X" is five words but asks for a real answer. Only explicit brevity
     * markers or very short non-question chatter (greetings, thanks) are BRIEF.
     */
    private val questionStarters = listOf(
        "ماذا", "ما ", "من ", "كيف", "لماذا", "هل ", "أين", "متى", "كم ",
        "what", "who", "how", "why", "when", "where", "which", "is ", "are ",
        "can ", "could ", "do ", "does ", "should ",
    )

    private const val BRIEF_MAX_CHATTER_WORDS = 3

    fun resolve(input: String, queryType: QueryType): ResponseDepth {
        val text = input.trim().lowercase()
        if (text.isBlank()) return ResponseDepth.STANDARD
        if (briefMarkers.any(text::contains)) return ResponseDepth.BRIEF
        if (openEndedMarkers.any(text::contains) || detailMarkers.any(text::contains) ||
            deliverableMarkers.any(text::contains)) {
            return ResponseDepth.DETAILED
        }
        if (queryType == QueryType.ANALYTICAL || queryType == QueryType.CREATIVE) {
            return ResponseDepth.DETAILED
        }
        val isQuestion = text.contains('?') || text.contains('؟') ||
            questionStarters.any { starter -> text.startsWith(starter) }
        val sentenceCount = text.count { it == '.' || it == '?' || it == '؟' || it == '\n' }
        val wordCount = text.split(Regex("\\s+")).count { it.isNotBlank() }
        return when {
            sentenceCount >= 2 || wordCount >= 28 -> ResponseDepth.DETAILED
            queryType == QueryType.SIMPLE && !isQuestion && wordCount <= BRIEF_MAX_CHATTER_WORDS -> ResponseDepth.BRIEF
            else -> ResponseDepth.STANDARD
        }
    }
}
