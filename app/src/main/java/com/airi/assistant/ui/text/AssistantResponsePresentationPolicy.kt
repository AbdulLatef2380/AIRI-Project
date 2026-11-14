package com.airi.assistant.ui.text

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

data class AssistantResponsePresentation(
    val style: AssistantResponseStyle,
    val surface: AssistantResponseSurface,
    val direction: AssistantContentKind,
    val emphasizeKeyPoints: Boolean,
    val supportsGenerativeUi: Boolean,
    val supportsAttachments: Boolean,
)

object AssistantResponsePresentationPolicy {
    fun plan(text: String, isStreaming: Boolean = false): AssistantResponsePresentation {
        val analysis = AssistantContentClassifier.analyse(text, isStreaming)
        val normalized = text.lowercase()
        val hasComparison = containsAny(normalized, "comparison", "compare", "versus", " vs ", "مقارنة", "مقابل", "الفرق بين", "أيهما")
        val hasDocument = containsAny(normalized, "document", "file", "report", "pdf", "docx", "markdown", "ملف", "مستند", "وثيقة", "تقرير")
        val hasEvaluation = containsAny(normalized, "evaluate", "evaluation", "review", "rating", "score", "pros", "cons", "تقييم", "مراجعة", "إيجابيات", "سلبيات", "المخاطر")
        val hasMap = containsAny(normalized, "map", "route", "directions", "coordinates", "location", "خريطة", "المسار", "إحداثيات", "الموقع")
        val hasGenerativeBlock = normalized.contains("```airi-ui")
        val hasData = analysis.containsTable || containsAny(normalized, "chart", "graph", "metrics", "statistics", "trend", "bar_chart", "diagram", "رسم بياني", "مخطط", "إحصاء", "مؤشرات", "اتجاه")
        val hasSymbolic = containsSymbolicContent(text)
        val hasCode = analysis.containsCode

        val style = when {
            hasMap -> AssistantResponseStyle.MAP_OR_ROUTE
            hasComparison -> AssistantResponseStyle.COMPARISON
            hasDocument -> AssistantResponseStyle.DOCUMENT
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
            hasComparison || analysis.containsTable -> AssistantResponseSurface.TABLE
            hasDocument -> AssistantResponseSurface.DOCUMENT_CARD
            hasCode -> AssistantResponseSurface.CODE_VIEWER
            hasEvaluation -> AssistantResponseSurface.SCORECARD
            hasData -> AssistantResponseSurface.CHART_OR_DIAGRAM
            hasGenerativeBlock -> AssistantResponseSurface.COMPOSED
            analysis.kind == AssistantContentKind.RICH_CONTENT -> AssistantResponseSurface.COMPOSED
            analysis.kind == AssistantContentKind.MARKDOWN -> AssistantResponseSurface.MARKDOWN
            else -> AssistantResponseSurface.PROSE
        }
        return AssistantResponsePresentation(
            style = style,
            surface = surface,
            direction = analysis.direction,
            emphasizeKeyPoints = style != AssistantResponseStyle.PLAIN_TEXT,
            supportsGenerativeUi = hasGenerativeBlock || style in setOf(
                AssistantResponseStyle.COMPARISON,
                AssistantResponseStyle.EVALUATION,
                AssistantResponseStyle.DATA_VISUAL,
                AssistantResponseStyle.MAP_OR_ROUTE,
                AssistantResponseStyle.EXPLANATION,
            ),
            supportsAttachments = style == AssistantResponseStyle.DOCUMENT || style == AssistantResponseStyle.CODE,
        )
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
