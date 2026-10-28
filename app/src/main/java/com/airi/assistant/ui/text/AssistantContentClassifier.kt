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
        return FENCED_CODE_REGEX.findAll(text).map { match ->
            AssistantCodeRange(
                start = match.range.first,
                end = match.range.last + 1,
                language = match.groupValues[1].ifBlank { "text" },
                code = match.groupValues[2].trimEnd(),
                isComplete = match.value.trimEnd().endsWith("```"),
            )
        }.toList()
    }

    fun isMarkdownTable(text: String): Boolean {
        val lines = text.lines().map(String::trim).filter(String::isNotEmpty)
        return lines.size >= 2 && lines.any { it.count { ch -> ch == '|' } >= 2 } &&
            lines.drop(1).any { it.matches(Regex("^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$")) }
    }

    fun looksStructured(text: String): Boolean {
        val value = text.trim()
        return (value.startsWith("{") && value.endsWith("}")) ||
            (value.startsWith("[") && value.endsWith("]")) ||
            (value.startsWith("<") && value.endsWith(">")) ||
            value.lines().count { it.matches(Regex("^\\s*[A-Za-z0-9_.-]+\\s*:\\s*.+$")) } >= 2 ||
            value.lines().count { it.matches(Regex("^\\s*(ERROR|WARN|INFO|DEBUG)\\b.*$")) } >= 2
    }

    fun looksMarkdown(text: String): Boolean = text.lines().any { line ->
        line.trimStart().startsWith("#") || line.trimStart().matches(Regex("^[-*+]\\s+.+")) ||
            line.trimStart().matches(Regex("^\\d+\\.\\s+.+")) || line.contains("**") ||
            line.contains("`") || line.trim() == ">" || line.trim().startsWith("> ") ||
            line.matches(Regex("^[-*_]{3,}\\s*$"))
    }

    private val FENCED_CODE_REGEX = Regex(
        "^[ \\t]*```([A-Za-z0-9_+#.-]*)[ \\t]*(?:\\r?\\n|\\z)([\\s\\S]*?)(?:^[ \\t]*```[ \\t]*(?=\\r?\\n|\\z)|\\z)",
        setOf(RegexOption.MULTILINE),
    )
}
