package com.airi.assistant.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AiriDatabaseMigrationTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private var database: AiriDatabase? = null

    @Before
    fun setUp() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        database?.close()
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun migratesVersionOneDataToVersionNineWithoutLoss() = runBlocking {
        createVersionOneDatabase()

        database = Room.databaseBuilder(context, AiriDatabase::class.java, DATABASE_NAME)
            .addMigrations(*AiriDatabase.migrations())
            .allowMainThreadQueries()
            .build()

        database!!.openHelper.writableDatabase

        val migrated = database!!.memoryDao().getSessionMessages("default")
        assertEquals(1, migrated.size)
        assertEquals("A preserved message", migrated.single().content)
        assertEquals("default", migrated.single().sessionId)
        assertEquals(0, migrated.single().feedback)
        assertNull(migrated.single().attachmentJson)

        val defaultSession = database!!.sessionDao().getSession("default")
        assertNotNull(defaultSession)
        assertEquals("Previous Chat", defaultSession!!.title)
        assertEquals(false, defaultSession.isPinned)

        val tables = database!!.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'table'"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(0))
            }
        }
        assertTrue("message_embedding" in tables)
        assertTrue("audit_log" in tables)
        assertTrue("workspace_artifact" in tables)
        val artifactColumns = database!!.openHelper.writableDatabase.query(
            "PRAGMA table_info('workspace_artifact')"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(cursor.getColumnIndexOrThrow("name")))
            }
        }
        assertTrue(setOf(
            "projectId", "taskId", "runId", "stepId", "toolId", "modelId",
            "provenanceSummary", "contentHash"
        ).all { it in artifactColumns })
        val artifactIndices = database!!.openHelper.writableDatabase.query(
            "PRAGMA index_list('workspace_artifact')"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue("index_workspace_artifact_projectId" in artifactIndices)
        assertTrue("index_workspace_artifact_projectId_taskId_runId_stepId" in artifactIndices)

        val memoryIndices = database!!.openHelper.writableDatabase.query(
            "PRAGMA index_list('episodic_memory')"
        ).use { cursor ->
            buildSet {
                while (cursor.moveToNext()) add(cursor.getString(1))
            }
        }
        assertTrue("index_episodic_memory_projectId_memoryScope" in memoryIndices)
        assertTrue("index_episodic_memory_expiresAtMs" in memoryIndices)
    }

    private fun createVersionOneDatabase() {
        context.openOrCreateDatabase(DATABASE_NAME, Context.MODE_PRIVATE, null).use { db ->
            db.execSQL(
                "CREATE TABLE episodic_memory (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "role TEXT NOT NULL, " +
                    "content TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL, " +
                    "emotionState TEXT)"
            )
            db.execSQL(
                "CREATE TABLE semantic_memory (" +
                    "key TEXT NOT NULL PRIMARY KEY, " +
                    "value TEXT NOT NULL, " +
                    "category TEXT NOT NULL, " +
                    "lastUpdated INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE behavior_stats (" +
                    "key TEXT NOT NULL PRIMARY KEY, " +
                    "shownCount INTEGER NOT NULL, " +
                    "acceptedCount INTEGER NOT NULL, " +
                    "dismissedCount INTEGER NOT NULL, " +
                    "lastUpdated INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE context_cache (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "screenText TEXT NOT NULL, " +
                    "sourceApp TEXT NOT NULL, " +
                    "detectedIntent TEXT NOT NULL, " +
                    "timestamp INTEGER NOT NULL)"
            )
            db.execSQL(
                "CREATE TABLE usage_stats (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "featureName TEXT NOT NULL, " +
                    "usageCount INTEGER NOT NULL, " +
                    "lastUsedTimestamp INTEGER NOT NULL)"
            )
            db.execSQL(
                "INSERT INTO episodic_memory(role, content, timestamp, emotionState) " +
                    "VALUES ('user', 'A preserved message', 1000, NULL)"
            )
            db.version = 1
        }
    }

    private companion object {
        const val DATABASE_NAME = "airi_migration_test"
    }
}
