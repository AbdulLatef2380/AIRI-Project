package com.airi.assistant.ai.prompt

import com.airi.assistant.memory.entity.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationSummaryWindowPolicyTest {
    @Test
    fun firstSummaryFoldsOnlyMessagesBeforeRecentWindow() {
        val history = (0 until 20).map { message(it) }
        val plan = ConversationSummaryWindowPolicy.select(history, "", 0)

        assertEquals((0L..7L).toList(), plan.olderTurns.map { it.id })
        assertEquals(8, plan.coverageThrough)
        assertTrue(plan.shouldSummarize)
    }

    @Test
    fun incrementalSummaryKeepsPriorSummaryAndOnlyUncoveredMessages() {
        val history = (0 until 24).map { message(it) }
        val plan = ConversationSummaryWindowPolicy.select(history, "accepted summary", 8)

        assertEquals("accepted summary", plan.previousSummary)
        assertEquals((8L..11L).toList(), plan.olderTurns.map { it.id })
        assertEquals(12, plan.coverageThrough)
    }

    @Test
    fun staleCoverageBeyondHistoryDropsStaleSummary() {
        val history = (0 until 10).map { message(it) }
        val plan = ConversationSummaryWindowPolicy.select(history, "stale", 20)

        assertEquals("", plan.previousSummary)
        assertTrue(plan.olderTurns.isEmpty())
        assertEquals(0, plan.coverageThrough)
    }

    private fun message(id: Int) = ChatMessage(
        id = id.toLong(), role = if (id % 2 == 0) "user" else "assistant", content = "turn-$id"
    )
}
