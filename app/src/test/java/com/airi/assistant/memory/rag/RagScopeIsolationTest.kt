package com.airi.assistant.memory.rag

import com.airi.assistant.memory.entity.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Test

class RagScopeIsolationTest {
    @Test
    fun chronologicalFallbackDoesNotExposeUserScopeWithoutOwnerIdentity() {
        val messages = listOf(
            ChatMessage(id = 1, sessionId = "session-a", role = "user", content = "private user memory", memoryScope = "USER"),
            ChatMessage(id = 2, sessionId = "session-a", role = "user", content = "session memory", memoryScope = "SESSION"),
            ChatMessage(id = 3, sessionId = "session-b", role = "user", content = "other session", memoryScope = "SESSION"),
        )

        val result = RagChronologicalFallback.select(
            messages = messages,
            sessionId = "session-a",
            query = "new query",
            limit = 10,
            projectId = "",
            maxPrivacyLevel = 1,
            nowMs = 100,
        )

        assertEquals(listOf("session memory"), result.map { it.content })
    }

    @Test
    fun projectMemoryRequiresTheRequestedProject() {
        val messages = listOf(
            ChatMessage(id = 1, sessionId = "session-a", role = "user", content = "wrong", memoryScope = "PROJECT", projectId = "p2"),
            ChatMessage(id = 2, sessionId = "session-a", role = "user", content = "right", memoryScope = "PROJECT", projectId = "p1"),
        )

        val result = RagChronologicalFallback.select(messages, "session-a", "query", 10, "p1", 1, 100)

        assertEquals(listOf("right"), result.map { it.content })
    }
}
