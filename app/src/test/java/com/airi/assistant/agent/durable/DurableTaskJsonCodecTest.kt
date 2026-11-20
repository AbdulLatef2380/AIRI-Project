package com.airi.assistant.agent.durable

import com.google.gson.Gson
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskJsonCodecTest {
    private val gson = Gson()
    private val codec = DurableTaskJsonCodec(gson)

    private fun fixture(id: String = "codec-task") = DurableTask(
        id = id,
        title = "Codec fixture",
        description = "test",
        agentId = "agent",
        input = "input"
    )

    @Test
    fun writesVersionedEnvelopeAndRoundTripsTaskAndExternalOperation() {
        val task = fixture().copy(
            externalOperations = listOf(
                ExternalOperation(
                    operationId = "op-1",
                    taskId = "codec-task",
                    runId = "run-1",
                    stepId = "step-1",
                    externalProvider = "ci",
                    externalReferenceId = "job-2",
                    startedAt = 10L,
                    nextPollAt = 20L,
                    timeoutPolicy = ExternalOperationTimeoutPolicy(deadlineAt = 100L),
                    retryPolicy = ExternalOperationRetryPolicy(maxAttempts = 5)
                )
            )
        )

        val json = codec.encode(listOf(task))
        val root = JsonParser.parseString(json).asJsonObject
        assertEquals(DurableTaskJsonCodec.CURRENT_SCHEMA_VERSION, root.get("schemaVersion").asInt)
        assertTrue(root.get("tasks").isJsonArray)
        assertEquals(listOf(task), codec.decode(json))
    }

    @Test
    fun readsLegacyRootArrayAndFillsNewFieldsBeforeRewriting() {
        val legacyTask = fixture()
        val legacyArray = JsonParser.parseString(gson.toJson(listOf(legacyTask))).asJsonArray
        legacyArray.single().asJsonObject.remove("externalOperations")
        legacyArray.single().asJsonObject.remove("budgetPauseReason")

        val decoded = codec.decode(legacyArray.toString())

        assertEquals("codec-task", decoded.single().id)
        assertEquals(emptyList<ExternalOperation>(), decoded.single().externalOperations)
        val migrated = JsonParser.parseString(codec.encode(decoded)).asJsonObject
        assertEquals(DurableTaskJsonCodec.CURRENT_SCHEMA_VERSION, migrated.get("schemaVersion").asInt)
    }

    @Test
    fun rejectsUnknownFutureSchemaAndMalformedEnvelopes() {
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode("""{"schemaVersion":999,"tasks":[]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            codec.decode("""{"schemaVersion":1,"tasks":{}}""")
        }
    }
}
