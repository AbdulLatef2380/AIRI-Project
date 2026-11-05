package com.airi.assistant.ai.agent.trace

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class AgentTraceStoreTest {

    private fun trace(id: String) = AgentTrace(
        id = id,
        traceId = id,
        originalInput = "input-$id",
        finalResult = "result-$id",
        success = true,
    )

    @Test
    fun deleteMatchingDoesNotOverwriteAcorruptStore() {
        val file = File.createTempFile("agent-traces-corrupt", ".json").apply { deleteOnExit() }
        val corrupt = "[{\"id\":\"valid-prefix\""
        file.writeText(corrupt)
        val store = AgentTraceStore(file)

        store.deleteMatching { true }

        assertEquals(corrupt, file.readText())
    }

    @Test
    fun concurrentSavesLeaveOneCompleteValidSnapshot() {
        val file = File.createTempFile("agent-traces-concurrent", ".json").apply { deleteOnExit() }
        val store = AgentTraceStore(file)
        val executor = Executors.newFixedThreadPool(4)
        repeat(40) { index ->
            executor.submit { store.save(listOf(trace("trace-$index"))) }
        }
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        val persisted = JSONArray(file.readText())
        assertEquals(1, persisted.length())
        assertTrue(persisted.getJSONObject(0).getString("id").startsWith("trace-"))
        assertEquals(1, store.load().size)
    }
}
