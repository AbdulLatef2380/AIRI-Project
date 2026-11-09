package com.airi.assistant.ui.run.code

/**
 * Part 5 §4 (Diff tab): a line diff, pure Kotlin so it is unit-tested without Android.
 *
 * Common leading and trailing lines are trimmed first; the changed middle is aligned with a
 * longest-common-subsequence table. A middle too large for the table ([MAX_CELLS]) is shown as
 * "all removed, then all added" instead of risking memory on a phone.
 */
enum class DiffKind { SAME, ADDED, REMOVED }

data class DiffLine(
    val kind: DiffKind,
    val text: String,
    /** 1-based line number in the old text; null for an added line. */
    val oldNumber: Int?,
    /** 1-based line number in the new text; null for a removed line. */
    val newNumber: Int?,
)

sealed interface DiffRow {
    data class Line(val line: DiffLine) : DiffRow
    /** [count] unchanged lines folded away between two changed regions. */
    data class Skipped(val count: Int) : DiffRow
}

data class DiffView(
    val rows: List<DiffRow>,
    val added: Int,
    val removed: Int,
    /** True when [rows] was cut at the row cap. */
    val truncated: Boolean,
) {
    val hasChanges: Boolean get() = added > 0 || removed > 0
}

object LineDiff {
    const val MAX_CELLS = 2_000_000L
    const val DEFAULT_CONTEXT = 3
    const val MAX_ROWS = 1_500

    fun splitLines(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        return text.replace("\r\n", "\n").replace('\r', '\n').removeSuffix("\n").split('\n')
    }

    fun compute(old: String, new: String): List<DiffLine> {
        val a = splitLines(old)
        val b = splitLines(new)
        var start = 0
        while (start < a.size && start < b.size && a[start] == b[start]) start++
        var endA = a.size
        var endB = b.size
        while (endA > start && endB > start && a[endA - 1] == b[endB - 1]) { endA--; endB-- }

        val ops = ArrayList<Pair<DiffKind, String>>(maxOf(a.size, b.size))
        for (i in 0 until start) ops += DiffKind.SAME to a[i]
        val midA = a.subList(start, endA)
        val midB = b.subList(start, endB)
        diffMiddle(midA, midB, ops)
        for (i in endA until a.size) ops += DiffKind.SAME to a[i]

        var oldNo = 0
        var newNo = 0
        return ops.map { (kind, text) ->
            when (kind) {
                DiffKind.SAME -> { oldNo++; newNo++; DiffLine(kind, text, oldNo, newNo) }
                DiffKind.REMOVED -> { oldNo++; DiffLine(kind, text, oldNo, null) }
                DiffKind.ADDED -> { newNo++; DiffLine(kind, text, null, newNo) }
            }
        }
    }

    private fun diffMiddle(a: List<String>, b: List<String>, out: MutableList<Pair<DiffKind, String>>) {
        if (a.isEmpty()) { b.forEach { out += DiffKind.ADDED to it }; return }
        if (b.isEmpty()) { a.forEach { out += DiffKind.REMOVED to it }; return }
        val n = a.size
        val m = b.size
        if (n.toLong() * m > MAX_CELLS) {
            a.forEach { out += DiffKind.REMOVED to it }
            b.forEach { out += DiffKind.ADDED to it }
            return
        }
        // lcs[i][j] = length of the longest common subsequence of a[i..] and b[j..].
        val width = m + 1
        val lcs = IntArray((n + 1) * width)
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                lcs[i * width + j] = if (a[i] == b[j]) {
                    lcs[(i + 1) * width + j + 1] + 1
                } else {
                    maxOf(lcs[(i + 1) * width + j], lcs[i * width + j + 1])
                }
            }
        }
        var i = 0
        var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { out += DiffKind.SAME to a[i]; i++; j++ }
                lcs[(i + 1) * width + j] >= lcs[i * width + j + 1] -> { out += DiffKind.REMOVED to a[i]; i++ }
                else -> { out += DiffKind.ADDED to b[j]; j++ }
            }
        }
        while (i < n) { out += DiffKind.REMOVED to a[i]; i++ }
        while (j < m) { out += DiffKind.ADDED to b[j]; j++ }
    }

    /**
     * Folds unchanged runs, keeping [context] lines around every change. Identical texts give an
     * empty row list. At most [maxRows] rows are returned and [DiffView.truncated] says so.
     */
    fun view(old: String, new: String, context: Int = DEFAULT_CONTEXT, maxRows: Int = MAX_ROWS): DiffView {
        val lines = compute(old, new)
        val added = lines.count { it.kind == DiffKind.ADDED }
        val removed = lines.count { it.kind == DiffKind.REMOVED }
        if (added == 0 && removed == 0) return DiffView(emptyList(), 0, 0, truncated = false)

        val keep = BooleanArray(lines.size)
        lines.forEachIndexed { index, line ->
            if (line.kind != DiffKind.SAME) {
                for (k in (index - context).coerceAtLeast(0)..(index + context).coerceAtMost(lines.lastIndex)) keep[k] = true
            }
        }
        val rows = ArrayList<DiffRow>()
        var skipped = 0
        lines.forEachIndexed { index, line ->
            if (keep[index]) {
                if (skipped > 0) { rows += DiffRow.Skipped(skipped); skipped = 0 }
                rows += DiffRow.Line(line)
            } else {
                skipped++
            }
        }
        if (skipped > 0) rows += DiffRow.Skipped(skipped)
        val truncated = rows.size > maxRows
        return DiffView(if (truncated) rows.take(maxRows) else rows, added, removed, truncated)
    }
}
