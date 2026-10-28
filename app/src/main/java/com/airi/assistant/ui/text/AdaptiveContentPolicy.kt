package com.airi.assistant.ui.text

/**
 * Semantic units exposed by the response presentation layer.
 * The UI must bind an action to one of these units, never to visible text by position.
 */
enum class ContentUnitKind {
    PROSE,
    CODE,
    TABLE,
    STRUCTURED_DATA,
}

enum class ContentUnitAction {
    COPY,
    SHARE,
    SAVE,
}

data class AssistantContentUnit(
    val id: String,
    val kind: ContentUnitKind,
    val source: String,
    val language: String? = null,
    val complete: Boolean = true,
    val actions: Set<ContentUnitAction>,
)

data class AdaptiveContentPlan(
    val units: List<AssistantContentUnit>,
    val messageActions: Set<ContentUnitAction>,
) {
    val hasIndependentUnits: Boolean
        get() = units.any { it.kind != ContentUnitKind.PROSE }
}

/** Pure policy: determines presentation and action scope from content semantics and state. */
object AdaptiveContentPolicy {
    fun plan(text: String, isStreaming: Boolean = false): AdaptiveContentPlan {
        if (text.isBlank()) return AdaptiveContentPlan(emptyList(), emptySet())

        val analysis = AssistantContentClassifier.analyse(text, isStreaming)
        val units = mutableListOf<AssistantContentUnit>().apply {
            analysis.codeBlocks.forEachIndexed { index, block ->
                add(
                    AssistantContentUnit(
                        id = "code-$index-${stableSuffix(block.code)}",
                        kind = if (block.language.equals("json", true)) ContentUnitKind.STRUCTURED_DATA else ContentUnitKind.CODE,
                        source = block.code,
                        language = block.language,
                        complete = block.isComplete,
                        actions = buildSet {
                            add(ContentUnitAction.COPY)
                            if (block.isComplete) {
                                add(ContentUnitAction.SHARE)
                                add(ContentUnitAction.SAVE)
                            }
                        },
                    ),
                )
            }
            if (analysis.containsTable) {
                add(
                    AssistantContentUnit(
                        id = "table-${stableSuffix(text)}",
                        kind = ContentUnitKind.TABLE,
                        source = text,
                        actions = setOf(ContentUnitAction.COPY, ContentUnitAction.SHARE),
                    ),
                )
            }
            if (isEmpty() && text.isNotBlank()) {
                add(
                    AssistantContentUnit(
                        id = "prose-${stableSuffix(text)}",
                        kind = ContentUnitKind.PROSE,
                        source = text,
                        actions = setOf(ContentUnitAction.COPY, ContentUnitAction.SHARE),
                    ),
                )
            }
        }.toList()

        // Message actions are deliberately separate from unit actions.
        val messageActions = buildSet {
            if (text.isNotBlank()) add(ContentUnitAction.COPY)
            if (!isStreaming && text.isNotBlank()) add(ContentUnitAction.SHARE)
        }
        return AdaptiveContentPlan(
            units = units,
            messageActions = messageActions,
        )
    }

    /** Spreadsheet-friendly representation, intentionally distinct from full-message copy. */
    fun tableClipboardText(text: String): String = parseMarkdownTable(text)
        .joinToString("\n") { row -> row.joinToString("\t") }

    /** Shareable table representation retains readable columns without surrounding prose. */
    fun tableShareText(text: String): String = parseMarkdownTable(text)
        .joinToString("\n") { row -> row.joinToString(" | ") }

    fun parseMarkdownTable(text: String): List<List<String>> = text.lines()
        .map(String::trim)
        .filter { it.contains('|') && it.trim('|', ' ', '\t').isNotEmpty() }
        .filterNot { it.matches(TABLE_SEPARATOR) }
        .map { line -> line.trim('|').split('|').map(String::trim) }
        .filter { it.isNotEmpty() }

    private fun stableSuffix(value: String): String = value.hashCode().toUInt().toString(16)

    private val TABLE_SEPARATOR = Regex(
        "^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$",
    )
}
