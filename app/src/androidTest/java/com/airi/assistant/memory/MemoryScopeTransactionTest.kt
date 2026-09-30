package com.airi.assistant.memory

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryScopeTransactionTest {
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
    fun duplicateChecksAreAtomicAndRespectScopeOwnership() = runBlocking {
        val dao = database.memoryDao()
        val writes = (0 until 16).map { index ->
            async(Dispatchers.IO) {
                dao.insertScopedLongTermMemoryIfAbsent(
                    ChatMessage(
                        sessionId = "user-session-$index",
                        role = "user",
                        content = "same user fact",
                        isMemory = true,
                        memoryScope = "USER"
                    ),
                    keepRecentPerScope = 50
                )
            }
        }.awaitAll()

        assertEquals(1, writes.count { it != null })
        assertNotNull(dao.findScopedLongTermMemoryId("USER", "other-session", "", "same user fact"))

        val projectA = dao.insertScopedLongTermMemoryIfAbsent(
            ChatMessage(sessionId = "s1", role = "user", content = "project fact", isMemory = true,
                projectId = "project-a", memoryScope = "PROJECT"),
            keepRecentPerScope = 50
        )
        val projectADuplicate = dao.insertScopedLongTermMemoryIfAbsent(
            ChatMessage(sessionId = "s2", role = "user", content = "project fact", isMemory = true,
                projectId = "project-a", memoryScope = "PROJECT"),
            keepRecentPerScope = 50
        )
        val projectB = dao.insertScopedLongTermMemoryIfAbsent(
            ChatMessage(sessionId = "s3", role = "user", content = "project fact", isMemory = true,
                projectId = "project-b", memoryScope = "PROJECT"),
            keepRecentPerScope = 50
        )

        assertNotNull(projectA)
        assertNull(projectADuplicate)
        assertNotNull(projectB)
    }

    @Test
    fun userScopeRetentionIsGlobalAndSessionDeletionPreservesOtherScopes() = runBlocking {
        val dao = database.memoryDao()
        (1..4).forEach { index ->
            val inserted = dao.insertScopedLongTermMemoryIfAbsent(
                ChatMessage(
                    sessionId = "session-$index",
                    role = "user",
                    content = "user fact $index",
                    timestamp = index.toLong(),
                    isMemory = true,
                    memoryScope = "USER"
                ),
                keepRecentPerScope = 2
            )
            assertNotNull(inserted)
        }
        dao.insertScopedLongTermMemoryIfAbsent(
            ChatMessage(sessionId = "session-4", role = "user", content = "session-only fact",
                isMemory = true, memoryScope = "SESSION"),
            keepRecentPerScope = 2
        )
        dao.insertScopedLongTermMemoryIfAbsent(
            ChatMessage(sessionId = "session-4", role = "user", content = "project-owned fact",
                isMemory = true, projectId = "project-x", memoryScope = "PROJECT"),
            keepRecentPerScope = 2
        )

        val beforeDelete = dao.getScopedLongTermMemories("session-4", "project-x", 3, System.currentTimeMillis(), 20)
        assertEquals(2, beforeDelete.count { it.memoryScope == "USER" })
        assertEquals(1, beforeDelete.count { it.memoryScope == "SESSION" })
        assertEquals(1, beforeDelete.count { it.memoryScope == "PROJECT" })

        dao.deleteLongTermMemoriesForSession("session-4")
        val afterDelete = dao.getScopedLongTermMemories("session-4", "project-x", 3, System.currentTimeMillis(), 20)

        assertEquals(2, afterDelete.count { it.memoryScope == "USER" })
        assertEquals(0, afterDelete.count { it.memoryScope == "SESSION" })
        assertEquals(1, afterDelete.count { it.memoryScope == "PROJECT" })
    }
}
