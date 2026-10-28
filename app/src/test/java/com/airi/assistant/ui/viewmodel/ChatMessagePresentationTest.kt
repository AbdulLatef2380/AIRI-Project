package com.airi.assistant.ui.viewmodel

import com.airi.assistant.ui.text.AssistantToolOutput
import com.airi.assistant.ui.text.ContentUnitAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatMessagePresentationTest {
    @Test
    fun assistantPresentationKeepsAgentTextAndToolResultsSeparate() {
        val toolOutput = AssistantToolOutput(
            id = "tool-42-1",
            toolName = "search",
            step = 1,
            text = "tool-only result",
        )
        val message = ChatMessage(
            text = "Agent-authored final answer",
            isUser = false,
            toolOutputs = listOf(toolOutput),
        )

        assertEquals("Agent-authored final answer", message.outputPresentation.agentOutput)
        assertEquals(listOf(toolOutput), message.outputPresentation.toolOutputs)
        assertTrue(ContentUnitAction.EXPAND in message.outputPresentation.toolOutputs.single().actions)
    }

    @Test
    fun userMessageCannotExposeAssistantToolOutputPresentation() {
        val message = ChatMessage(
            text = "User question",
            isUser = true,
            toolOutputs = listOf(
                AssistantToolOutput("tool-1", "search", 1, "not a user output"),
            ),
        )

        assertEquals("", message.outputPresentation.agentOutput)
        assertTrue(message.outputPresentation.toolOutputs.isEmpty())
    }
}
