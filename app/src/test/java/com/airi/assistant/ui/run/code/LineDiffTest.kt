package com.airi.assistant.ui.run.code

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LineDiffTest {
    private fun kinds(old: String, new: String) = LineDiff.compute(old, new).map { it.kind }

    @Test fun splitLinesHandlesEmptyTrailingNewlineAndCrLf() {
        assertEquals(emptyList<String>(), LineDiff.splitLines(""))
        assertEquals(listOf("a"), LineDiff.splitLines("a\n"))
        assertEquals(listOf("a", "b"), LineDiff.splitLines("a\r\nb"))
        assertEquals(listOf(""), LineDiff.splitLines("\n"))
    }

    @Test fun identicalTextHasNoChangesAndNoRows() {
        val view = LineDiff.view("a\nb\nc", "a\nb\nc")
        assertFalse(view.hasChanges)
        assertTrue(view.rows.isEmpty())
    }

    @Test fun addedLineIsMarkedAndNumbered() {
        val lines = LineDiff.compute("a\nc", "a\nb\nc")
        assertEquals(listOf(DiffKind.SAME, DiffKind.ADDED, DiffKind.SAME), lines.map { it.kind })
        val added = lines[1]
        assertEquals("b", added.text)
        assertNull(added.oldNumber)
        assertEquals(2, added.newNumber)
        assertEquals(3, lines[2].newNumber)
        assertEquals(2, lines[2].oldNumber)
    }

    @Test fun removedLineIsMarked() {
        val lines = LineDiff.compute("a\nb\nc", "a\nc")
        assertEquals(listOf(DiffKind.SAME, DiffKind.REMOVED, DiffKind.SAME), lines.map { it.kind })
        assertNull(lines[1].newNumber)
        assertEquals(2, lines[1].oldNumber)
    }

    @Test fun changedLineIsRemovedThenAdded() {
        assertEquals(listOf(DiffKind.REMOVED, DiffKind.ADDED), kinds("x = 1", "x = 2"))
    }

    @Test fun createdFileIsAllAdded() {
        val view = LineDiff.view("", "one\ntwo")
        assertEquals(2, view.added)
        assertEquals(0, view.removed)
    }

    @Test fun insertionInTheMiddleKeepsSurroundingLinesSame() {
        val lines = LineDiff.compute("1\n2\n3\n4\n5", "1\n2\nX\n3\n4\n5")
        assertEquals(1, lines.count { it.kind == DiffKind.ADDED })
        assertEquals(0, lines.count { it.kind == DiffKind.REMOVED })
        assertEquals(5, lines.count { it.kind == DiffKind.SAME })
    }

    @Test fun reconstructingBothSidesFromTheDiffGivesTheOriginalTexts() {
        val old = "a\nb\nc\nd\ne\nf"
        val new = "a\nc\nd\nX\nY\nf\ng"
        val lines = LineDiff.compute(old, new)
        assertEquals(old, lines.filter { it.kind != DiffKind.ADDED }.joinToString("\n") { it.text })
        assertEquals(new, lines.filter { it.kind != DiffKind.REMOVED }.joinToString("\n") { it.text })
    }

    @Test fun unchangedRunsAreFoldedWithContext() {
        val old = (1..30).joinToString("\n") { "line $it" }
        val new = old.replace("line 15", "line fifteen")
        val view = LineDiff.view(old, new, context = 2)
        val skipped = view.rows.filterIsInstance<DiffRow.Skipped>()
        assertEquals(2, skipped.size)
        // 31 rows (30 + 1 added); the changed pair sits at rows 14-15 (0-based), 2 rows of context each side.
        assertEquals(12, skipped[0].count)
        assertEquals(13, skipped[1].count)
        assertEquals(1, view.added)
        assertEquals(1, view.removed)
    }

    @Test fun rowCapMarksTheViewTruncated() {
        val view = LineDiff.view("", (1..50).joinToString("\n") { "n$it" }, maxRows = 10)
        assertEquals(10, view.rows.size)
        assertTrue(view.truncated)
    }

    @Test fun hugeMiddleFallsBackToRemoveThenAddInsteadOfBuildingATable() {
        val n = 1_600 // 1600 * 1600 > MAX_CELLS
        val old = (0 until n).joinToString("\n") { "old$it" }
        val new = (0 until n).joinToString("\n") { "new$it" }
        val lines = LineDiff.compute(old, new)
        assertEquals(n, lines.count { it.kind == DiffKind.REMOVED })
        assertEquals(n, lines.count { it.kind == DiffKind.ADDED })
        assertEquals(DiffKind.REMOVED, lines.first().kind)
    }
}
