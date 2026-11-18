package com.airi.assistant.ui.text

import com.airi.assistant.ui.generative.GenerativeUiCompiler

/**
 * Deterministic presentation contract shared by model guidance and native rendering.
 * It never executes tools and never changes the assistant's answer; it only describes
 * the most useful native surface for the content that is already available.
 */
enum class AssistantResponseStyle {
    PLAIN_TEXT,
    EXPLANATION,
    COMPARISON,
    DOCUMENT,
    CODE,
    SYMBOLIC,
    EVALUATION,
    DATA_VISUAL,
    MAP_OR_ROUTE,
    MIXED,
}

enum class AssistantResponseSurface {
    PROSE,
    MARKDOWN,
    TABLE,
    CODE_VIEWER,
    DOCUMENT_CARD,
    SCORECARD,
    CHART_OR_DIAGRAM,
    MAP_CARD,
    COMPOSED,
}

enum class AssistantWorkKind {
    GENERAL,
    DOCUMENT_CREATION,
    IMAGE_CREATION,
    PRESENTATION_CREATION,
    WEBSITE_CREATION,
    APP_CREATION,
    GAME_CREATION,
    PROMPT_ENGINEERING,
}

data class AssistantResponsePresentation(
    val style: AssistantResponseStyle,
    val surface: AssistantResponseSurface,
    val direction: AssistantContentKind,
    val emphasizeKeyPoints: Boolean,
    val supportsGenerativeUi: Boolean,
    val supportsAttachments: Boolean,
    val workKind: AssistantWorkKind,
    val isLongRunningConversation: Boolean,
    val hasExecutionContext: Boolean,
    /** Full AIRI composition contract used by native surfaces and future clients. */
    val composition: ResponseComposition,
)

object AssistantResponsePresentationPolicy {
    fun plan(
        text: String,
        isStreaming: Boolean = false,
        context: AssistantConversationContext = AssistantConversationContext(),
    ): AssistantResponsePresentation {
        val analysis = AssistantContentClassifier.analyse(text, isStreaming)
        // Lane keywords come from the visible answer only; the airi-ui payload is
        // interface data and must not be read as map/data/code content.
        val visibleText = GenerativeUiCompiler.withoutGenerativeUi(text)
        val normalized = visibleText.lowercase()
        val hasComparison = containsAny(normalized, "comparison", "compare", "versus", " vs ", "مقارنة", "قارن", "مقابل", "الفرق بين", "أيهما")
        val hasDocument = containsAny(normalized, "document", "file", "report", "pdf", "docx", "markdown", "ملف", "مستند", "وثيقة", "تقرير")
        val hasEvaluation = containsAny(normalized, "evaluate", "evaluation", "review", "rating", "score", "pros", "cons", "تقييم", "مراجعة", "إيجابيات", "سلبيات", "المخاطر")
        val hasMap = containsAny(normalized, "map", "route", "directions", "coordinates", "location", "خريطة", "المسار", "إحداثيات", "الموقع")
        val hasGenerativeBlock = text.contains("```airi-ui", ignoreCase = true)
        val hasData = analysis.containsTable || containsAny(normalized, "chart", "graph", "metrics", "statistics", "trend", "bar_chart", "diagram", "رسم بياني", "مخطط", "إحصاء", "مؤشرات", "اتجاه")
        val hasSymbolic = containsSymbolicContent(visibleText)
        val hasCode = analysis.codeBlocks.any { !it.language.equals("airi-ui", ignoreCase = true) }
        val workKind = workKind(normalized)
        val longRunning = context.messageCount >= 20 || context.totalChars >= 12_000 || context.sessionAgeHours?.let { it >= 4 } == true
        val composition = ConversationResponseCompositionPlanner.plan(
            ResponseRequestProfile(
                userText = context.latestUserText,
                answerText = text,
                messageCount = context.messageCount,
                totalChars = context.totalChars,
                sessionAgeHours = context.sessionAgeHours,
                isStreaming = isStreaming,
                evidence = ResponseEvidence(
                    hasAttachments = context.hasAttachments,
                    hasToolResults = context.hasToolOutputs,
                    hasRemoteSources = context.hasRemoteSources,
                    hasSkillsOrConnectors = context.hasSkillsOrConnectors,
                    hasFiles = context.hasFiles,
                    hasImages = context.hasImages,
                    hasAudio = context.hasAudio,
                    hasVideo = context.hasVideo,
                    hasPresentation = context.hasPresentation,
                    hasWebsite = context.hasWebsite,
                    hasApplication = context.hasApplication,
                    hasGame = context.hasGame,
                ),
            ),
        )

        val style = when {
            hasMap -> AssistantResponseStyle.MAP_OR_ROUTE
            hasComparison -> AssistantResponseStyle.COMPARISON
            hasDocument || workKind == AssistantWorkKind.DOCUMENT_CREATION -> AssistantResponseStyle.DOCUMENT
            hasCode -> AssistantResponseStyle.CODE
            hasEvaluation -> AssistantResponseStyle.EVALUATION
            hasData -> AssistantResponseStyle.DATA_VISUAL
            hasGenerativeBlock -> AssistantResponseStyle.MIXED
            hasSymbolic -> AssistantResponseStyle.SYMBOLIC
            analysis.kind == AssistantContentKind.RICH_CONTENT -> AssistantResponseStyle.EXPLANATION
            else -> AssistantResponseStyle.PLAIN_TEXT
        }
        val surface = when {
            hasMap -> AssistantResponseSurface.MAP_CARD
            hasGenerativeBlock -> AssistantResponseSurface.COMPOSED
            workKind in setOf(AssistantWorkKind.WEBSITE_CREATION, AssistantWorkKind.APP_CREATION, AssistantWorkKind.GAME_CREATION) -> AssistantResponseSurface.COMPOSED
            workKind == AssistantWorkKind.PRESENTATION_CREATION || workKind == AssistantWorkKind.IMAGE_CREATION -> AssistantResponseSurface.DOCUMENT_CARD
            hasComparison || analysis.containsTable -> AssistantResponseSurface.TABLE
            hasDocument -> AssistantResponseSurface.DOCUMENT_CARD
            hasCode -> AssistantResponseSurface.CODE_VIEWER
            hasEvaluation -> AssistantResponseSurface.SCORECARD
            hasData -> AssistantResponseSurface.CHART_OR_DIAGRAM
            analysis.kind == AssistantContentKind.RICH_CONTENT -> AssistantResponseSurface.COMPOSED
            analysis.kind == AssistantContentKind.MARKDOWN -> AssistantResponseSurface.MARKDOWN
            else -> AssistantResponseSurface.PROSE
        }
        return AssistantResponsePresentation(
            style = style,
            surface = surface,
            direction = analysis.direction,
            emphasizeKeyPoints = style != AssistantResponseStyle.PLAIN_TEXT || longRunning,
            supportsGenerativeUi = hasGenerativeBlock,
            supportsAttachments = context.hasAttachments || style == AssistantResponseStyle.DOCUMENT ||
                style == AssistantResponseStyle.CODE || workKind != AssistantWorkKind.GENERAL,
            workKind = workKind,
            isLongRunningConversation = longRunning,
            hasExecutionContext = context.hasToolOutputs || context.hasRemoteSources || context.hasSkillsOrConnectors,
            composition = composition,
        )
    }

    private fun workKind(value: String): AssistantWorkKind = when {
        containsAny(value, "presentation", "slides", "عرض تقديمي", "شرائح") -> AssistantWorkKind.PRESENTATION_CREATION
        containsAny(value, "image", "illustration", "صورة", "رسم توضيحي") -> AssistantWorkKind.IMAGE_CREATION
        containsAny(value, "website", "web app", "موقع", "صفحة ويب") -> AssistantWorkKind.WEBSITE_CREATION
        containsAny(value, "mobile app", "application", "تطبيق") -> AssistantWorkKind.APP_CREATION
        containsAny(value, "game", "لعبة") -> AssistantWorkKind.GAME_CREATION
        containsAny(value, "prompt", "system instruction", "برومبت", "تعليمات النظام") -> AssistantWorkKind.PROMPT_ENGINEERING
        containsAny(value, "document", "report", "pdf", "docx", "مستند", "تقرير", "وثيقة") -> AssistantWorkKind.DOCUMENT_CREATION
        else -> AssistantWorkKind.GENERAL
    }

    private fun containsAny(value: String, vararg needles: String): Boolean = needles.any(value::contains)

    private fun containsSymbolicContent(text: String): Boolean {
        val lines = text.lines()
        return lines.count { line ->
            line.contains("→") || line.contains("⇒") || line.contains("∑") ||
                line.contains("=") && line.any(Char::isDigit)
        } >= 2
    }
}
