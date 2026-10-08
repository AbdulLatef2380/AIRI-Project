package com.airi.assistant.memory.rag

import com.airi.assistant.memory.entity.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RagChronologicalFallbackTest {
    @Test
    fun returnsRecentSafeMessagesInChronologicalOrderAndExcludesCurrentQuery() {
        val messages = listOf(
            msg(1, "user", "old", timestamp = 1),
            msg(2, "assistant", "middle", timestamp = 2),
            msg(3, "user", "current input", timestamp = 3),
            msg(4, "assistant", "latest answer", timestamp = 4),
        )

        val result = RagChronologicalFallback.select(
            messages = messages,
            sessionId = "default",
            query = "current input",
            limit = 5,
            projectId = "",
            maxPrivacyLevel = 1,
            nowMs = 100,
        )

        assertEquals(listOf("old", "middle", "latest answer"), result.map { it.content })
        assertTrue(result.all { it.source == "CHRONOLOGICAL_FALLBACK" })
    }

    @Test
    fun excludesExpiredPrivateAndWrongProjectMessages() {
        val messages = listOf(
            msg(1, "user", "expired", timestamp = 1).copy(expiresAtMs = 10),
            msg(2, "user", "private", timestamp = 2).copy(privacyLevel = 2),
            msg(3, "assistant", "wrong project", timestamp = 3).copy(memoryScope = "PROJECT", projectId = "other"),
            msg(4, "assistant", "allowed", timestamp = 4),
        )

        val result = RagChronologicalFallback.select(
            messages = messages,
            sessionId = "default",
            query = "new question",
            limit = 5,
            projectId = "project-a",
            maxPrivacyLevel = 1,
            nowMs = 20,
        )

        assertEquals(listOf("allowed"), result.map { it.content })
    }

    private fun msg(id: Long, role: String, content: String, timestamp: Long) = ChatMessage(
        id = id, role = role, content = content, timestamp = timestamp
    )
}
