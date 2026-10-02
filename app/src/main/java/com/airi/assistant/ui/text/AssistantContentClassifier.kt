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

data class AssistantCodeBlock(val language: String, val code: String)

/**
 * Classifies before rendering. Incomplete fences are deliberately retained as
 * streaming content instead of being permanently classified as prose.
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
        val hasFence = text.contains("```")
        val incompleteFence = hasFence && text.split("```").size % 2 == 0
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
            codeBlocks.isNotEmpty() -> AssistantContentKind.CODE_BLOCK
            looksStructured(text) -> AssistantContentKind.STRUCTURED_CODE
            looksMarkdown(text) -> AssistantContentKind.MARKDOWN
            direction == AssistantContentKind.MIXED_DIRECTION -> AssistantContentKind.MIXED_DIRECTION
            else -> AssistantContentKind.PLAIN_TEXT
        }
        return AssistantContentAnalysis(kind, codeBlocks.isNotEmpty() || hasFence, hasTable, long, direction, codeBlocks)
    }

    fun fencedCodeBlocks(text: String): List<AssistantCodeBlock> {
        val regex = Regex("```([A-Za-z0-9_+#.-]*)\\s*\\n?([\\s\\S]*?)(?:```|$)")
        return regex.findAll(text).map { match ->
            AssistantCodeBlock(match.groupValues[1].ifBlank { "text" }, match.groupValues[2].trimEnd())
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
}
