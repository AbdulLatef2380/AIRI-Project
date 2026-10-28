package com.airi.assistant.ui.text

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveContentPolicyTest {
    @Test
    fun codeUnitActionsAreIndependentFromMessageActions() {
        val text = "شرح قبل الكود\n```bash\ngit status\n```\nشرح بعد الكود"
        val plan = AdaptiveContentPolicy.plan(text)
        val code = plan.units.single { it.kind == ContentUnitKind.CODE }

        assertTrue(ContentUnitAction.COPY in code.actions)
        assertTrue(ContentUnitAction.SHARE in code.actions)
        assertTrue(ContentUnitAction.SAVE in code.actions)
        assertTrue(ContentUnitAction.COPY in plan.messageActions)
        assertTrue(ContentUnitAction.SHARE in plan.messageActions)
        assertFalse(code.source.contains("شرح قبل"))
        assertFalse(code.source.contains("شرح بعد"))
    }

    @Test
    fun incompleteStreamingCodeCannotBeSharedOrSaved() {
        val plan = AdaptiveContentPolicy.plan("```kotlin\nfun initialize() {", isStreaming = true)
        val code = plan.units.single()

        assertEquals(ContentUnitKind.CODE, code.kind)
        assertTrue(ContentUnitAction.COPY in code.actions)
        assertFalse(ContentUnitAction.SHARE in code.actions)
        assertFalse(ContentUnitAction.SAVE in code.actions)
        assertFalse(ContentUnitAction.SHARE in plan.messageActions)
    }

    @Test
    fun tableCopyUsesIndependentSpreadsheetFriendlyScope() {
        val text = "شرح\n| Model | Status |\n| --- | --- |\n| Local | Ready |\nبعد الشرح"
        val plan = AdaptiveContentPolicy.plan(text)
        val table = plan.units.single { it.kind == ContentUnitKind.TABLE }

        assertEquals("Model\tStatus\nLocal\tReady", AdaptiveContentPolicy.tableClipboardText(table.source))
        assertEquals("Model | Status\nLocal | Ready", AdaptiveContentPolicy.tableShareText(table.source))
        assertTrue(ContentUnitAction.COPY in table.actions)
        assertTrue(ContentUnitAction.SHARE in table.actions)
    }

    @Test
    fun plainMessageKeepsOnlyMessageAndProseScope() {
        val plan = AdaptiveContentPolicy.plan("نعم، هذا ممكن.")
        assertEquals(ContentUnitKind.PROSE, plan.units.single().kind)
        assertTrue(ContentUnitAction.COPY in plan.messageActions)
        assertTrue(ContentUnitAction.SHARE in plan.messageActions)
    }

    @Test
    fun mixedReplyKeepsOrderedProseCodeAndTrailingProseUnits() {
        val text = "شرح قبل الكود\n```python\nprint(1)\n```\nشرح بعد الكود"
        val units = AdaptiveContentPolicy.plan(text, messageId = "message-1").units

        assertEquals(
            listOf(ContentUnitKind.PROSE, ContentUnitKind.CODE, ContentUnitKind.PROSE),
            units.map { it.kind },
        )
        assertEquals("شرح قبل الكود\n", units[0].source)
        assertEquals("print(1)", units[1].source)
        assertEquals("\nشرح بعد الكود", units[2].source)
    }

    @Test
    fun tableUnitContainsOnlyTableSourceAndKeepsSurroundingProse() {
        val text = "شرح\n| Model | Status |\n| --- | --- |\n| Local | Ready |\nبعد الشرح"
        val units = AdaptiveContentPolicy.plan(text, messageId = "message-2").units
        val table = units.single { it.kind == ContentUnitKind.TABLE }

        assertEquals(listOf(ContentUnitKind.PROSE, ContentUnitKind.TABLE, ContentUnitKind.PROSE), units.map { it.kind })
        assertEquals("| Model | Status |\n| --- | --- |\n| Local | Ready |", table.source)
        assertFalse(table.source.contains("شرح"))
    }

    @Test
    fun blockIdentityUsesMessageAndSourcePositionAcrossEquivalentPlans() {
        val text = "شرح\n```kotlin\nval x = 1\n```\nملاحظات"
        val first = AdaptiveContentPolicy.plan(text, messageId = "stable-message")
        val second = AdaptiveContentPolicy.plan(text, messageId = "stable-message")

        assertEquals(first.units.map { it.id }, second.units.map { it.id })
        assertEquals("stable-message-code-4-1", first.units.single { it.kind == ContentUnitKind.CODE }.id)
    }

    @Test
    fun contentTargetsKeepMessageAndBlockScopesSeparate() {
        val message = ContentTarget.Message("m-1", "شرح\n```kotlin\nval x = 1\n```")
        val block = ContentTarget.Block("m-1", "b-1", ContentUnitKind.CODE, "val x = 1", "kotlin")

        assertEquals(message.source, message.copyPayload)
        assertEquals(message.source, message.sharePayload)
        assertEquals("val x = 1", block.copyPayload)
        assertEquals("val x = 1", block.sharePayload)
    }

    @Test
    fun tableContentTargetUsesScopedTablePayloads() {
        val block = ContentTarget.Block(
            messageId = "m-2",
            blockId = "table-1",
            kind = ContentUnitKind.TABLE,
            source = "| A | B |\n|---|---|\n| 1 | 2 |",
        )

        assertEquals("A\tB\n1\t2", block.copyPayload)
        assertEquals("A | B\n1 | 2", block.sharePayload)
    }

    @Test
    fun streamingStagesKeepCodeIdentityAndGateActionsUntilFenceCloses() {
        val stages = listOf(
            "شرح قبل الكود",
            "شرح قبل الكود\n```python",
            "شرح قبل الكود\n```python\nprint(1)",
            "شرح قبل الكود\n```python\nprint(1)\n```",
            "شرح قبل الكود\n```python\nprint(1)\n```\nشرح بعد الكود",
        )
        val plans = stages.map { text ->
            AdaptiveContentPolicy.plan(text, isStreaming = true, messageId = "stream-1")
        }
        val codeUnits = plans.map { plan -> plan.units.single { it.kind == ContentUnitKind.CODE } }

        assertEquals(codeUnits.map { it.id }.distinct().single(), codeUnits.first().id)
        assertEquals("print(1)", codeUnits[3].source)
        assertFalse(ContentUnitAction.SHARE in codeUnits[1].actions)
        assertFalse(ContentUnitAction.SAVE in codeUnits[2].actions)
        assertTrue(ContentUnitAction.SHARE in codeUnits[3].actions)
        assertTrue(ContentUnitAction.SAVE in codeUnits[3].actions)
        assertEquals(
            listOf(ContentUnitKind.PROSE, ContentUnitKind.CODE, ContentUnitKind.PROSE),
            plans.last().units.map { it.kind },
        )
    }
}
