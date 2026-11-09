package com.airi.assistant.execution.backend

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LocalToolCallGrammarTest {
    @Test fun grammarListsOnlyAdmittedToolNames() {
        val g = LocalToolCallGrammar.build(listOf("write_file", "terminal_execute"))
        assertNotNull(g)
        assertTrue(g!!.contains("write_file"))
        assertTrue(g.contains("terminal_execute"))
        assertTrue(g.contains("root ::="))
        assertTrue(!g.contains("web_search"))
    }

    @Test fun unsafeNamesAreDroppedAndEmptyListGivesNull() {
        assertNull(LocalToolCallGrammar.build(emptyList()))
        assertNull(LocalToolCallGrammar.build(listOf("bad name\"; evil", "")))
        val g = LocalToolCallGrammar.build(listOf("ok_tool", "bad\"name"))!!
        assertTrue(g.contains("ok_tool") && !g.contains("bad\"name"))
    }
}
