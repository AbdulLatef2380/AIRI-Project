package com.airi.assistant.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDaoInsertIdTest {

    private lateinit var database: AiriDatabase

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AiriDatabase::class.java
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun editingLongTermMemoryChangesContentAndPreservesScopedMetadata() = runBlocking {
        val dao = database.memoryDao()
        val memoryId = dao.insertMessage(
            ChatMessage(
                sessionId = "memory-edit-session",
                role = "assistant",
                content = "Original approved memory",
                isMemory = true,
                projectId = "project-memory-edit",
                memorySource = "USER_EXPLICIT",
                provenance = "User approved correction",
                confidence = 0.8f,
                importance = 73,
                memoryScope = "PROJECT",
                privacyLevel = 2,
                expiresAtMs = 9_999L
            )
        )
        val updated = dao.updateLongTermMemory(
            memoryId = memoryId,
            content = "Edited approved memory",
            provenance = "User approved correction",
            confidence = 1f,
            importance = 73,
            projectId = "project-memory-edit",
            memoryScope = "PROJECT",
            privacyLevel = 2,
            expiresAtMs = 9_999L,
            updatedAtMs = 10_000L
        )
        val stored = dao.getMessageById(memoryId)!!

        assertEquals(1, updated)
        assertEquals("Edited approved memory", stored.content)
        assertEquals("project-memory-edit", stored.projectId)
        assertEquals("PROJECT", stored.memoryScope)
        assertEquals(2, stored.privacyLevel)
        assertEquals(73, stored.importance)
        assertEquals(9_999L, stored.expiresAtMs)
        assertEquals("User approved correction", stored.provenance)

        val chatId = dao.insertMessage(ChatMessage(sessionId = "memory-edit-session", role = "user", content = "Normal chat"))
        assertEquals(
            0,
            dao.updateLongTermMemory(
                memoryId = chatId,
                content = "Must not change",
                provenance = "unused",
                confidence = 1f,
                importance = 1,
                projectId = "",
                memoryScope = "SESSION",
                privacyLevel = 1,
                expiresAtMs = -1L,
                updatedAtMs = 10_000L
            )
        )
        assertEquals("Normal chat", dao.getMessageById(chatId)!!.content)
    }

    @Test
    fun insertMessageReturnsThePersistedRowId() = runBlocking {
        val dao = database.memoryDao()
        val firstId = dao.insertMessage(
            ChatMessage(sessionId = "insert-id-test", role = "user", content = "First message")
        )
        val secondId = dao.insertMessage(
            ChatMessage(sessionId = "insert-id-test", role = "assistant", content = "Second message")
        )

        val storedIds = dao.getSessionMessages("insert-id-test").map { it.id }.toSet()

        assertTrue(firstId > 0L)
        assertTrue(secondId > firstId)
        assertEquals(setOf(firstId, secondId), storedIds)
    }
}
