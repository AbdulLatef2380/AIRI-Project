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
        assertFalse(ContentUnitAction.EXPAND in code.actions)
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
}
