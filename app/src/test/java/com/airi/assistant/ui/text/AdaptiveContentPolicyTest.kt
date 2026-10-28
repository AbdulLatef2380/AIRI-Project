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
        assertTrue(ContentUnitAction.EXPAND in code.actions)
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
        assertTrue(ContentUnitAction.EXPAND in code.actions)
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
        assertTrue(ContentUnitAction.SPEAK in plan.messageActions)
        assertTrue(ContentUnitAction.FULLSCREEN in plan.messageActions)
        assertTrue(ContentUnitAction.EXPORT_PDF in plan.messageActions)
        assertTrue(ContentUnitAction.EXPORT_MARKDOWN in plan.messageActions)
    }

    @Test
    fun streamingMessageDoesNotExposeFinalOnlyActions() {
        val actions = AdaptiveContentPolicy.plan("partial response", isStreaming = true).messageActions
        assertTrue(ContentUnitAction.COPY in actions)
        assertFalse(ContentUnitAction.SHARE in actions)
        assertFalse(ContentUnitAction.SPEAK in actions)
        assertFalse(ContentUnitAction.FULLSCREEN in actions)
        assertFalse(ContentUnitAction.EXPORT_PDF in actions)
        assertFalse(ContentUnitAction.EXPORT_MARKDOWN in actions)
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
        assertEquals("stable-message-code-0", first.units.single { it.kind == ContentUnitKind.CODE }.id)
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
        val codeUnits = plans.mapNotNull { plan -> plan.units.firstOrNull { it.kind == ContentUnitKind.CODE } }

        assertEquals(4, codeUnits.size)
        assertEquals(codeUnits.map { it.id }.distinct().single(), codeUnits.first().id)
        assertEquals("print(1)", codeUnits[2].source)
        assertFalse(ContentUnitAction.SHARE in codeUnits[0].actions)
        assertFalse(ContentUnitAction.SAVE in codeUnits[1].actions)
        assertTrue(ContentUnitAction.SHARE in codeUnits[2].actions)
        assertTrue(ContentUnitAction.SAVE in codeUnits[2].actions)
        assertEquals(
            listOf(ContentUnitKind.PROSE, ContentUnitKind.CODE, ContentUnitKind.PROSE),
            plans.last().units.map { it.kind },
        )
    }

    @Test
    fun codeIdentitySurvivesProseArrivingBeforeTheExistingBlock() {
        val first = AdaptiveContentPolicy.plan(
            "شرح قبل الكود\n```kotlin\nval x = 1\n```",
            messageId = "stream-2",
        )
        val later = AdaptiveContentPolicy.plan(
            "عنوان جديد\nشرح قبل الكود\n```kotlin\nval x = 1\n```",
            messageId = "stream-2",
        )

        assertEquals(
            first.units.single { it.kind == ContentUnitKind.CODE }.id,
            later.units.single { it.kind == ContentUnitKind.CODE }.id,
        )
    }

    @Test
    fun sessionPreservesEveryExistingUnitWhenNewBlocksAreInsertedBeforeThem() {
        val session = AdaptiveContentSession("stream-insert")
        val existingText = """Intro
```kotlin
val kept = 1
```
| A | B |
| --- | --- |
| 1 | 2 |
Tail"""
        val before = session.plan(existingText, isStreaming = true)
        val inserted = session.plan(
            """New lead
```python
print(0)
```
$existingText""",
            isStreaming = true,
        )

        assertTrue(before.units.isNotEmpty())
        assertTrue(
            "Missing existing unit IDs: ${before.units.filter { old -> inserted.units.none { it.id == old.id } }}",
            before.units.all { old -> inserted.units.any { it.id == old.id } },
        )
        assertEquals(
            setOf(ContentUnitKind.PROSE, ContentUnitKind.CODE, ContentUnitKind.TABLE),
            before.units.map { it.kind }.toSet(),
        )
        assertEquals(2, inserted.units.count { it.kind == ContentUnitKind.CODE })
        assertEquals(
            inserted.units.size,
            inserted.units.map { it.id }.distinct().size,
        )
    }

    @Test
    fun sessionKeepsExistingIdentityWhenAnIdenticalBlockIsPrepended() {
        val session = AdaptiveContentSession("duplicate-stream")
        val text = "```kotlin\nval same = 1\n```"
        val existing = session.plan(text, isStreaming = true).units.single()
        val updated = session.plan("$text\n$text", isStreaming = true)
        val laterDuplicate = updated.units.last()

        assertEquals(existing.id, laterDuplicate.id)
        assertEquals(2, updated.units.map { it.id }.distinct().size)
    }

    @Test
    fun sessionKeepsIdentityAsStreamingUnitGrowsAndCloses() {
        val session = AdaptiveContentSession("growing-stream")
        val partial = session.plan("```kotlin\nfun", isStreaming = true).units.single()
        val complete = session.plan("```kotlin\nfun ready() = true\n```", isStreaming = false).units.single()

        assertEquals(partial.id, complete.id)
        assertTrue(complete.complete)
    }

    @Test
    fun toolOutputsRemainSeparateFromAgentContentUnits() {
        val toolOutput = AssistantToolOutput(
            id = "tool-1-1",
            toolName = "search",
            step = 1,
            text = "tool-only result",
        )
        val plan = AdaptiveContentPolicy.plan(
            text = "Agent final answer",
            messageId = "separate-output",
            toolOutputs = listOf(toolOutput),
        )

        assertEquals(listOf("Agent final answer"), plan.units.map { it.source })
        assertEquals(listOf(toolOutput), plan.toolOutputs)
        assertTrue(ContentUnitAction.EXPAND in plan.toolOutputs.single().actions)
    }

    @Test
    fun markdownOutsideTheArpeArtifactProfileStaysInProseScope() {
        val text = "> block quote\n~~~kotlin\nval x = 1\n~~~\n- [ ] task\n[link](https://example.com)"
        val plan = AdaptiveContentPolicy.plan(text, messageId = "markdown-limits")

        assertEquals(listOf(ContentUnitKind.PROSE), plan.units.map { it.kind })
        assertEquals(text, plan.units.single().source)
        assertFalse(ContentUnitAction.SAVE in plan.messageActions)
        assertFalse(ContentUnitAction.EXPAND in plan.units.single().actions)
    }

    @Test
    fun contentActionPolicyRequiresDeclaredActionAndCompleteSave() {
        val incomplete = ContentTarget.Block(
            messageId = "m",
            blockId = "b",
            kind = ContentUnitKind.CODE,
            source = "partial",
            complete = false,
            actions = setOf(ContentUnitAction.COPY, ContentUnitAction.SAVE, ContentUnitAction.EXPAND),
        )
        assertTrue(incomplete.canPerform(ContentUnitAction.COPY))
        assertTrue(incomplete.canPerform(ContentUnitAction.EXPAND))
        assertFalse(incomplete.canPerform(ContentUnitAction.SAVE))
        assertFalse(incomplete.canPerform(ContentUnitAction.SHARE))
    }

    @Test
    fun messagePolicyAllowsOnlyDeclaredMessageActions() {
        val target = ContentTarget.Message(
            messageId = "m",
            source = "answer",
            actions = setOf(ContentUnitAction.COPY, ContentUnitAction.EXPORT_MARKDOWN),
        )
        assertTrue(target.canPerform(ContentUnitAction.COPY))
        assertTrue(target.canPerform(ContentUnitAction.EXPORT_MARKDOWN))
        assertFalse(target.canPerform(ContentUnitAction.SPEAK))
        assertFalse(target.canPerform(ContentUnitAction.FULLSCREEN))
    }

    @Test
    fun toolOutputActionsHaveIndependentTargetScope() {
        val target = AssistantToolOutput(
            id = "tool-1",
            toolName = "search",
            step = 1,
            text = "result",
            actions = setOf(ContentUnitAction.EXPAND),
        ).toContentTarget("message-1")
        assertTrue(target.canPerform(ContentUnitAction.EXPAND))
        assertFalse(target.canPerform(ContentUnitAction.COPY))
        assertEquals("result", target.source)
    }
}
