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
