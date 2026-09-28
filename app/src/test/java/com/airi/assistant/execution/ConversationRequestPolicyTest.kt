package com.airi.assistant.execution

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConversationRequestPolicyTest {
    @Test
    fun currentUserTurnIsSentExactlyOnceAfterPriorHistory() {
        val turns = listOf(
            ExecutionRequest.ConversationTurn("user", "previous question"),
            ExecutionRequest.ConversationTurn("assistant", "previous answer"),
            ExecutionRequest.ConversationTurn("user", "current question"),
        )

        val request = ConversationRequestPolicy.project(turns, currentPromptText = "current question")

        assertEquals("current question", request.prompt)
        assertEquals(turns.dropLast(1), request.conversationHistory)
        assertEquals(
            1,
            request.conversationHistory.count { it.content == "current question" } +
                if (request.prompt == "current question") 1 else 0,
        )
    }

    @Test
    fun toolContinuationKeepsHistoryAndDoesNotAppendBlankUserTurn() {
        val turns = listOf(
            ExecutionRequest.ConversationTurn("user", "question"),
            ExecutionRequest.ConversationTurn("assistant", "tool call"),
            ExecutionRequest.ConversationTurn("user", "tool result"),
        )

        val request = ConversationRequestPolicy.project(turns, currentPromptText = null)

        assertEquals("", request.prompt)
        assertEquals(turns, request.conversationHistory)
    }

    @Test(expected = IllegalArgumentException::class)
    fun currentPromptMustBeFinalUserTurn() {
        ConversationRequestPolicy.project(
            listOf(ExecutionRequest.ConversationTurn("assistant", "not a user turn")),
            currentPromptText = "current question",
        )
    }
}
