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
    val messageId: String,
    val units: List<AssistantContentUnit>,
    val messageActions: Set<ContentUnitAction>,
) {
    val hasIndependentUnits: Boolean
        get() = units.any { it.kind != ContentUnitKind.PROSE }
}

/** Pure policy: determines presentation and action scope from content semantics and state. */
object AdaptiveContentPolicy {
    fun plan(
        text: String,
        isStreaming: Boolean = false,
        messageId: String = stableMessageId(text),
    ): AdaptiveContentPlan {
        if (text.isBlank()) return AdaptiveContentPlan(messageId, emptyList(), emptySet())

        val analysis = AssistantContentClassifier.analyse(text, isStreaming)
        val units = parseUnits(text, analysis, messageId)
        val messageActions = buildSet {
            add(ContentUnitAction.COPY)
            if (!isStreaming) add(ContentUnitAction.SHARE)
        }
        return AdaptiveContentPlan(
            messageId = messageId,
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

    private fun parseUnits(
        text: String,
        analysis: AssistantContentAnalysis,
        messageId: String,
    ): List<AssistantContentUnit> {
        val matches = mutableListOf<UnitMatch>()
        AssistantContentClassifier.fencedCodeRanges(text).forEach { block ->
            matches += UnitMatch(
                start = block.start,
                end = block.end,
                kind = if (block.language.equals("json", true)) ContentUnitKind.STRUCTURED_DATA else ContentUnitKind.CODE,
                source = block.code,
                language = block.language,
                complete = block.isComplete,
            )
        }
        markdownTableRange(text)?.let { table ->
            matches += UnitMatch(
                start = table.start,
                end = table.end,
                kind = ContentUnitKind.TABLE,
                source = table.source,
            )
        }

        if (matches.isEmpty() && analysis.codeBlocks.isEmpty()) {
            return listOf(
                proseUnit(messageId, text, 0, 0),
            )
        }

        val ordered = matches.sortedWith(compareBy<UnitMatch> { it.start }.thenBy { it.end })
        val units = mutableListOf<AssistantContentUnit>()
        val kindOccurrences = mutableMapOf<ContentUnitKind, Int>()
        var cursor = 0
        var occurrence = 0
        ordered.forEach { match ->
            if (match.start < cursor) return@forEach
            addProse(units, text.substring(cursor, match.start), cursor, messageId, occurrence++)
            val kindOccurrence = kindOccurrences.getOrDefault(match.kind, 0)
            kindOccurrences[match.kind] = kindOccurrence + 1
            units += match.toUnit(messageId, kindOccurrence, occurrence++)
            cursor = match.end
        }
        addProse(units, text.substring(cursor), cursor, messageId, occurrence)
        return units
    }

    private fun addProse(
        units: MutableList<AssistantContentUnit>,
        source: String,
        start: Int,
        messageId: String,
        occurrence: Int,
    ) {
        if (source.isBlank()) return
        units += proseUnit(messageId, source, start, occurrence)
    }

    private fun proseUnit(
        messageId: String,
        source: String,
        start: Int,
        occurrence: Int,
    ) = AssistantContentUnit(
        id = unitId(messageId, ContentUnitKind.PROSE, start, occurrence, occurrence),
        kind = ContentUnitKind.PROSE,
        source = source,
        actions = setOf(ContentUnitAction.COPY, ContentUnitAction.SHARE),
    )

    private data class UnitMatch(
        val start: Int,
        val end: Int,
        val kind: ContentUnitKind,
        val source: String,
        val language: String? = null,
        val complete: Boolean = true,
    ) {
        fun toUnit(messageId: String, kindOccurrence: Int, occurrence: Int) = AssistantContentUnit(
            id = unitId(messageId, kind, start, kindOccurrence, occurrence),
            kind = kind,
            source = source,
            language = language,
            complete = complete,
            actions = buildSet {
                add(ContentUnitAction.COPY)
                if (kind == ContentUnitKind.TABLE || complete) add(ContentUnitAction.SHARE)
                if ((kind == ContentUnitKind.CODE || kind == ContentUnitKind.STRUCTURED_DATA) && complete) {
                    add(ContentUnitAction.SAVE)
                }
            },
        )
    }

    private fun markdownTableRange(text: String): TableRange? {
        val lines = text.split('\n')
        val starts = IntArray(lines.size)
        for (index in 1 until lines.size) {
            starts[index] = starts[index - 1] + lines[index - 1].length + 1
        }
        for (separatorIndex in 1 until lines.size) {
            if (!lines[separatorIndex].trim().matches(TABLE_SEPARATOR)) continue
            var startLine = separatorIndex - 1
            while (startLine > 0 && lines[startLine - 1].contains('|')) startLine--
            var endLine = separatorIndex
            while (endLine + 1 < lines.size && lines[endLine + 1].contains('|')) endLine++
            val start = starts[startLine]
            val end = (starts[endLine] + lines[endLine].length).coerceAtMost(text.length)
            return TableRange(start, end, text.substring(start, end))
        }
        return null
    }

    private data class TableRange(val start: Int, val end: Int, val source: String)

    private fun unitId(
        messageId: String,
        kind: ContentUnitKind,
        start: Int,
        kindOccurrence: Int,
        occurrence: Int,
    ): String = if (kind == ContentUnitKind.PROSE) {
        "$messageId-${kind.name.lowercase()}-$start-$occurrence"
    } else {
        // Block identity is tied to its semantic kind and ordinal, not its source offset.
        // This keeps a code/table card stable when prose arrives before it during streaming.
        "$messageId-${kind.name.lowercase()}-$kindOccurrence"
    }

    private fun stableMessageId(value: String): String =
        "message-${value.hashCode().toUInt().toString(16)}"

    private val TABLE_SEPARATOR = Regex(
        "^\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)+\\|?$",
    )
}
