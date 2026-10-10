package com.airi.assistant.agent.loop.tool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaPromptBudgetTest {
    @Test
    fun omitsWholeSchemasAndKeepsOnlyToolsActuallyRendered() {
        val oversized = ToolSchema("oversized_tool", "x".repeat(400))
        val small = ToolSchema("small_tool", "Read a deterministic value")

        val selection = ToolSchemaPromptBudget.select(listOf(oversized, small), maxChars = 180)

        assertEquals(listOf("small_tool"), selection.tools.map { it.name })
        assertTrue(selection.promptBlock.length <= 180)
        assertTrue(selection.promptBlock.contains("small_tool"))
        assertFalse(selection.promptBlock.contains("oversized_tool"))
        assertTrue(selection.omittedCount >= 1)
        assertEquals(listOf("oversized_tool"), selection.omittedToolNames)
        assertTrue(selection.promptBlock.contains("omitted by context budget"))
    }

    @Test
    fun doesNotCutASchemaWhenNoToolFits() {
        val oversized = ToolSchema("oversized_tool", "x".repeat(400))

        val selection = ToolSchemaPromptBudget.select(listOf(oversized), maxChars = 180)

        assertTrue(selection.tools.isEmpty())
        assertTrue(selection.promptBlock.length <= 180)
        assertFalse(selection.promptBlock.contains("oversized_tool"))
        assertEquals(1, selection.omittedCount)
        assertEquals(listOf("oversized_tool"), selection.omittedToolNames)
    }
}
