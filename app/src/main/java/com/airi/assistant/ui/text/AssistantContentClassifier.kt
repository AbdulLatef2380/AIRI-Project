package com.airi.assistant.ui.text

/** Content kinds used by the chat presentation layer. This is pure and side-effect free. */
enum class AssistantContentKind {
    EMPTY_STATE,
    PLAIN_TEXT,
    MARKDOWN,
    CODE_BLOCK,
    STRUCTURED_CODE,
    TABLE,
    RICH_CONTENT,
    MIXED_DIRECTION,
    RTL_TEXT,
    LTR_TEXT,
    STREAMING_CONTENT,
}

data class AssistantContentAnalysis(
    val kind: AssistantContentKind,
    val containsCode: Boolean,
    val containsTable: Boolean,
    val isLong: Boolean,
    val direction: AssistantContentKind,
    val codeBlocks: List<AssistantCodeBlock> = emptyList(),
)

data class AssistantCodeBlock(val language: String, val code: String, val isComplete: Boolean = true)

data class AssistantCodeRange(
    val start: Int,
    val end: Int,
    val language: String,
    val code: String,
    val isComplete: Boolean,
)

/**
 * Classifies before rendering. ARPE intentionally extracts only standalone,
 * non-nested triple-backtick fences with a simple language token. Other Markdown
 * syntax stays in the prose renderer and does not receive block-scoped actions.
 * Incomplete fences remain streaming content until a closing fence arrives.
 */
object AssistantContentClassifier {
    const val RICH_CONTENT_THRESHOLD = 600

    fun analyse(text: String, isStreaming: Boolean = false): AssistantContentAnalysis {
        if (text.isBlank()) return AssistantContentAnalysis(
            kind = AssistantContentKind.EMPTY_STATE,
            containsCode = false,
            containsTable = false,
            isLong = false,
            direction = AssistantContentKind.PLAIN_TEXT,
        )
        val codeBlocks = fencedCodeBlocks(text)
        val hasCompleteCode = codeBlocks.any { it.isComplete }
        val hasFence = codeBlocks.isNotEmpty()
        val incompleteFence = codeBlocks.any { !it.isComplete }
        val hasTable = isMarkdownTable(text)
        val long = text.length >= RICH_CONTENT_THRESHOLD
        val direction = when (LanguageRuntimeManager.analyseDirection(text)) {
            LanguageRuntimeManager.DominantDirection.RTL -> AssistantContentKind.RTL_TEXT
            LanguageRuntimeManager.DominantDirection.LTR -> AssistantContentKind.LTR_TEXT
            LanguageRuntimeManager.DominantDirection.MIXED -> AssistantContentKind.MIXED_DIRECTION
            LanguageRuntimeManager.DominantDirection.NEUTRAL -> AssistantContentKind.PLAIN_TEXT
        }
        val kind = when {
            isStreaming && (hasFence || incompleteFence) -> AssistantContentKind.STREAMING_CONTENT
            long -> AssistantContentKind.RICH_CONTENT
            hasTable -> AssistantContentKind.TABLE
            hasCompleteCode -> AssistantContentKind.CODE_BLOCK
            looksStructured(text) -> AssistantContentKind.STRUCTURED_CODE
            looksMarkdown(text) -> AssistantContentKind.MARKDOWN
            direction == AssistantContentKind.MIXED_DIRECTION -> AssistantContentKind.MIXED_DIRECTION
            else -> AssistantContentKind.PLAIN_TEXT
        }
        return AssistantContentAnalysis(kind, codeBlocks.any { it.isComplete } || hasFence, hasTable, long, direction, codeBlocks)
    }

    fun fencedCodeBlocks(text: String): List<AssistantCodeBlock> {
        return fencedCodeRanges(text).map { AssistantCodeBlock(it.language, it.code, it.isComplete) }
    }

    fun fencedCodeRanges(text: String): List<AssistantCodeRange> {
        val ranges = mutableListOf<AssistantCodeRange>()
        var cursor = 0
        while (cursor < text.length) {
            val lineEnd = text.indexOf('\n', cursor).let { if (it < 0) text.length else it }
            val line = text.substring(cursor, lineEnd).removeSuffix("\r")
            val opening = FENCE_OPEN.find(line)
            if (opening == null) {
                cursor = if (lineEnd < text.length) lineEnd + 1 else text.length
                continue
            }
            val fenceLength = opening.groupValues[1].length
            val language = opening.groupValues[2].trim().split(Regex("\\s+")).firstOrNull().orEmpty().ifBlank { "text" }
            val codeStart = if (lineEnd < text.length) lineEnd + 1 else text.length
            var scan = codeStart
            var closingStart = -1
            var closingEnd = text.length
            while (scan < text.length) {
                val candidateEnd = text.indexOf('\n', scan).let { if (it < 0) text.length else it }
                val candidate = text.substring(scan, candidateEnd).removeSuffix("\r")
                if (FENCE_CLOSE.matches(candidate) && candidate.count { it == '`' } >= fenceLength) {
                    closingStart = scan
                    closingEnd = if (candidateEnd < text.length) candidateEnd + 1 else candidateEnd
                    break
                }
                scan = if (candidateEnd < text.length) candidateEnd + 1 else text.length
            }
            val codeEnd = if (closingStart >= 0) closingStart else text.length
            ranges += AssistantCodeRange(
                start = cursor,
                end = closingEnd,
                language = language,
                code = text.substring(codeStart, codeEnd).trimEnd('\r', '\n'),
                isComplete = closingStart >= 0,
            )
            cursor = closingEnd
        }
        return ranges
    }

    fun isMarkdownTable(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        return lines.size >= 2 && lines.any { it.count { ch -> ch == '|' } >= 2 } &&
            lines.drop(1).any { TABLE_SEPARATOR.matches(it) }
    }

    fun looksStructured(text: String): Boolean {
        val value = text.trim()
        return (value.startsWith("{") && value.endsWith("}")) ||
            (value.startsWith("[") && value.endsWith("]")) ||
            (value.startsWith("<") && value.endsWith(">")) ||
            value.lines().count { KEY_VALUE_LINE.matches(it) } >= 2 ||
            value.lines().count { LOG_LINE.matches(it) } >= 2
    }

    fun looksMarkdown(text: String): Boolean = text.lines().any { line ->
        line.trimStart().startsWith("#") || BULLET_LINE.matches(line.trimStart()) ||
            ORDERED_LINE.matches(line.trimStart()) || line.contains("**") ||
            line.contains("`") || line.trim() == ">" || line.trim().startsWith("> ") ||
            HORIZONTAL_RULE.matches(line)
    }

    private val TABLE_SEPARATOR = Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")
    private val KEY_VALUE_LINE = Regex("^\\s*[A-Za-z0-9_.-]+\\s*:\\s*.+$")
    private val LOG_LINE = Regex("^\\s*(ERROR|WARN|INFO|DEBUG)\\b.*$")
    private val BULLET_LINE = Regex("^[-*+•◦▪]\\s+.+")
    private val ORDERED_LINE = Regex("^[0-9٠-٩۰-۹]+[.)]\\s+.+")
    private val HORIZONTAL_RULE = Regex("^[-*_]{3,}\\s*$")

    private val FENCE_OPEN = Regex("^[ \\t]*(`{3,})([^`]*)$")
    private val FENCE_CLOSE = Regex("^[ \\t]*`{3,}[ \\t]*$")
}
