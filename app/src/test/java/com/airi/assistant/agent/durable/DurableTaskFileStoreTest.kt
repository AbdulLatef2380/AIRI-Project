package com.airi.assistant.agent.durable

import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskFileStoreTest {
    private val directory = Files.createTempDirectory("airi-task-store-test").toFile()
    private val file = File(directory, "durable_tasks.json")
    private val codec = DurableTaskJsonCodec()
    private val store = DurableTaskFileStore(file, codec)

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    private fun task(id: String = "file-store-task") = DurableTask(
        id = id,
        title = "File store fixture",
        description = "test",
        agentId = "agent",
        input = "fixture"
    )

    @Test
    fun missingFileIsReportedWithoutCreatingOrBlockingStore() {
        assertEquals(DurableTaskFileReadResult.Missing, store.read())
        assertFalse(file.exists())

        val expected = task()
        store.write(listOf(expected))
        val loaded = store.read() as DurableTaskFileReadResult.Loaded
        assertEquals(listOf(expected), loaded.tasks)
    }

    @Test
    fun writeUsesVersionedEnvelopeAndReplacesThePreviousDocument() {
        store.write(listOf(task("first")))
        store.write(listOf(task("second")))

        val document = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(DurableTaskJsonCodec.CURRENT_SCHEMA_VERSION, document.get("schemaVersion").asInt)
        assertEquals(listOf("second"), store.read().let { (it as DurableTaskFileReadResult.Loaded).tasks.map(DurableTask::id) })
        assertFalse(File(directory, "${file.name}.tmp").exists())
    }

    @Test
    fun malformedExistingFileIsPreservedAndBlocksWrites() {
        val corruptContents = "{not-valid-json"
        file.writeText(corruptContents)

        val result = store.read()
        assertTrue(result is DurableTaskFileReadResult.Invalid)
        assertTrue(runCatching { store.write(listOf(task())) }.exceptionOrNull() is IOException)
        assertEquals(corruptContents, file.readText())
        assertFalse(File(directory, "${file.name}.tmp").exists())
    }

    @Test
    fun legacyArrayCanBeReadAndExplicitlyMigratedToVersionedEnvelope() {
        val gson = Gson()
        val legacy = JsonParser.parseString(gson.toJson(listOf(task()))).asJsonArray
        legacy.single().asJsonObject.remove("externalOperations")
        file.writeText(legacy.toString())

        val loaded = store.read() as DurableTaskFileReadResult.Loaded
        assertEquals("file-store-task", loaded.tasks.single().id)
        assertEquals(emptyList<ExternalOperation>(), loaded.tasks.single().externalOperations)

        store.write(loaded.tasks)
        val migrated = JsonParser.parseString(file.readText()).asJsonObject
        assertEquals(DurableTaskJsonCodec.CURRENT_SCHEMA_VERSION, migrated.get("schemaVersion").asInt)
    }
}
