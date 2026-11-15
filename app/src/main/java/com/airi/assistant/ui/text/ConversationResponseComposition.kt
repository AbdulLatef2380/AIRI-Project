package com.airi.assistant.ui.text

/**
 * AIRI's response DNA: a pure, provider-independent composition contract.
 *
 * The model may produce content, but it never decides the Android layout. This
 * planner combines the admitted request, observable execution evidence, and
 * conversation state into a bounded set of native presentation sections.
 */
enum class ResponseLane {
    CONVERSATION,
    EXPLANATION,
    COMPARISON,
    DOCUMENT,
    CODE,
    SYMBOLIC,
    EVALUATION,
    DATA,
    MAP,
    MEDIA,
    ARTIFACT,
    WORKSPACE,
}

enum class ResponseDensity { COMPACT, BALANCED, DEEP }

enum class ResponseSectionKind {
    LEAD,
    ANSWER,
    CONTEXT,
    EVIDENCE,
    TABLE,
    CHART,
    DIAGRAM,
    MAP,
    CODE,
    ATTACHMENTS,
    TOOL_RESULTS,
    ARTIFACTS,
    MEDIA,
    ACTIONS,
    SOURCES,
    TRACE,
    NEXT_STEP,
}

data class ResponseEvidence(
    val hasAttachments: Boolean = false,
    val hasToolResults: Boolean = false,
    val hasRemoteSources: Boolean = false,
    val hasSkillsOrConnectors: Boolean = false,
    val hasFiles: Boolean = false,
    val hasImages: Boolean = false,
    val hasAudio: Boolean = false,
    val hasVideo: Boolean = false,
    val hasPresentation: Boolean = false,
    val hasWebsite: Boolean = false,
    val hasApplication: Boolean = false,
    val hasGame: Boolean = false,
)

data class ResponseRequestProfile(
    val userText: String = "",
    val answerText: String = "",
    val messageCount: Int = 0,
    val totalChars: Int = 0,
    val sessionAgeHours: Int? = null,
    val isStreaming: Boolean = false,
    val evidence: ResponseEvidence = ResponseEvidence(),
)

data class ResponseComposition(
    val lane: ResponseLane,
    val density: ResponseDensity,
    val sections: List<ResponseSectionKind>,
    val progressive: Boolean,
    val preserveInteractiveState: Boolean,
    val discloseExecution: Boolean,
    val discloseUncertainty: Boolean,
    val allowGenerativeUi: Boolean,
    val isLongRunning: Boolean,
)

object ConversationResponseCompositionPlanner {
    fun plan(profile: ResponseRequestProfile): ResponseComposition {
        val request = profile.userText.lowercase()
        val answer = profile.answerText.lowercase()
        val combined = "$request\n$answer"
        val evidence = profile.evidence
        val longRunning = profile.messageCount >= 20 ||
            profile.totalChars >= 12_000 ||
            profile.sessionAgeHours?.let { it >= 4 } == true
        val hasComparison = containsAny(combined, "compare", "comparison", "versus", " vs ", "مقارنة", "قارن", "مقابل", "الفرق بين", "أيهما")
        val hasDocument = containsAny(combined, "document", "report", "pdf", "docx", "markdown", "ملف", "مستند", "وثيقة", "تقرير")
        val hasNativeGenerativeUi = answer.contains("```airi-ui")
        val hasCode = (fencedCode(profile.answerText) && !hasNativeGenerativeUi) ||
            containsAny(request, "code", "program", "script", "كود", "برمج", "شفرة")
        val hasEvaluation = containsAny(combined, "evaluate", "evaluation", "review", "rating", "score", "pros", "cons", "تقييم", "مراجعة", "إيجابيات", "سلبيات", "المخاطر")
        val hasMap = containsAny(combined, "map", "route", "directions", "coordinates", "location", "خريطة", "المسار", "إحداثيات", "الموقع")
        val hasData = containsAny(combined, "chart", "graph", "metrics", "statistics", "trend", "bar_chart", "diagram", "رسم بياني", "مخطط", "إحصاء", "مؤشرات", "اتجاه")
        val creationRequest = containsAny(request, "create", "generate", "build", "make", "أنشئ", "إنشاء", "ولّد", "صمّم", "ابنِ")
        val requestedMedia = creationRequest && containsAny(request, "image", "illustration", "audio", "sound", "voice", "video", "صورة", "رسم توضيحي", "صوت", "فيديو")
        val requestedWorkspace = creationRequest && containsAny(request, "presentation", "slides", "website", "web app", "mobile app", "application", "game", "عرض تقديمي", "شرائح", "موقع", "تطبيق", "لعبة")
        val requestedArtifact = creationRequest && containsAny(request, "file", "document", "report", "pdf", "docx", "ملف", "مستند", "تقرير", "وثيقة")
        val hasSymbolic = profile.answerText.lines().count { line ->
            line.contains("→") || line.contains("⇒") || line.contains("∑") ||
                (line.contains("=") && line.any(Char::isDigit))
        } >= 2
        val hasInteractive = hasNativeGenerativeUi || hasData || hasMap || hasComparison
        val lane = when {
            evidence.hasImages || evidence.hasAudio || evidence.hasVideo || requestedMedia -> ResponseLane.MEDIA
            evidence.hasPresentation || evidence.hasWebsite || evidence.hasApplication || evidence.hasGame || requestedWorkspace -> ResponseLane.WORKSPACE
            evidence.hasFiles || hasDocument || requestedArtifact -> ResponseLane.ARTIFACT
            hasMap -> ResponseLane.MAP
            hasComparison -> ResponseLane.COMPARISON
            hasCode -> ResponseLane.CODE
            hasEvaluation -> ResponseLane.EVALUATION
            hasData -> ResponseLane.DATA
            hasSymbolic -> ResponseLane.SYMBOLIC
            containsAny(combined, "explain", "how does", "why", "اشرح", "كيف", "لماذا") -> ResponseLane.EXPLANATION
            else -> ResponseLane.CONVERSATION
        }
        val density = when {
            longRunning || profile.answerText.length > 8_000 -> ResponseDensity.DEEP
            profile.answerText.length < 700 && !evidence.hasToolResults -> ResponseDensity.COMPACT
            else -> ResponseDensity.BALANCED
        }
        val sections = sectionsFor(lane, evidence, hasInteractive, longRunning)
        return ResponseComposition(
            lane = lane,
            density = density,
            sections = sections,
            progressive = profile.isStreaming,
            preserveInteractiveState = hasInteractive || evidence.hasAttachments || longRunning,
            discloseExecution = evidence.hasToolResults || evidence.hasRemoteSources || evidence.hasSkillsOrConnectors,
            discloseUncertainty = evidence.hasRemoteSources || lane == ResponseLane.MAP || lane == ResponseLane.EVALUATION,
            allowGenerativeUi = !profile.isStreaming && hasInteractive && lane !in setOf(ResponseLane.CODE, ResponseLane.SYMBOLIC),
            isLongRunning = longRunning,
        )
    }

    private fun sectionsFor(
        lane: ResponseLane,
        evidence: ResponseEvidence,
        interactive: Boolean,
        longRunning: Boolean,
    ): List<ResponseSectionKind> = buildList {
        add(ResponseSectionKind.LEAD)
        when (lane) {
            ResponseLane.COMPARISON -> add(ResponseSectionKind.TABLE)
            ResponseLane.CODE, ResponseLane.SYMBOLIC -> add(ResponseSectionKind.CODE)
            ResponseLane.DATA -> {
                add(ResponseSectionKind.EVIDENCE)
                add(ResponseSectionKind.CHART)
            }
            ResponseLane.MAP -> add(ResponseSectionKind.MAP)
            ResponseLane.DOCUMENT -> {
                add(ResponseSectionKind.ANSWER)
                add(ResponseSectionKind.ARTIFACTS)
            }
            ResponseLane.ARTIFACT -> add(ResponseSectionKind.ARTIFACTS)
            ResponseLane.MEDIA -> add(ResponseSectionKind.MEDIA)
            ResponseLane.WORKSPACE -> {
                add(ResponseSectionKind.ARTIFACTS)
                add(ResponseSectionKind.MEDIA)
                add(ResponseSectionKind.ACTIONS)
            }
            ResponseLane.EVALUATION -> {
                add(ResponseSectionKind.EVIDENCE)
                add(ResponseSectionKind.TABLE)
                add(ResponseSectionKind.NEXT_STEP)
            }
            ResponseLane.EXPLANATION -> add(ResponseSectionKind.ANSWER)
            ResponseLane.CONVERSATION -> add(ResponseSectionKind.ANSWER)
        }
        if (evidence.hasAttachments) add(ResponseSectionKind.ATTACHMENTS)
        if (evidence.hasToolResults) add(ResponseSectionKind.TOOL_RESULTS)
        if (interactive && ResponseSectionKind.ACTIONS !in this) add(ResponseSectionKind.ACTIONS)
        if (evidence.hasRemoteSources) add(ResponseSectionKind.SOURCES)
        if (longRunning || evidence.hasToolResults || evidence.hasSkillsOrConnectors) add(ResponseSectionKind.TRACE)
        add(ResponseSectionKind.NEXT_STEP)
    }

    private fun fencedCode(value: String): Boolean = value.contains("```")
    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any(value::contains)
}
