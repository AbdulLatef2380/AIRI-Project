package com.airi.assistant.ai.agent.trace

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    @Test
    fun targetIdentityRoundTripsThroughPersistentStore() {
        val file = File.createTempFile("agent-traces-target", ".json").apply { deleteOnExit() }
        val store = AgentTraceStore(file)
        store.save(listOf(
            trace("target-trace").copy(
                model = "requested-model",
                provider = "requested-provider",
                requestedModel = "requested-model",
                requestedProvider = "requested-provider",
                resolvedModel = "local-model",
                resolvedProvider = "local",
                fallbackReason = "cloud unavailable",
            ),
        ))

        val restored = store.load().single()
        assertEquals("requested-model", restored.requestedModel)
        assertEquals("requested-provider", restored.requestedProvider)
        assertEquals("local-model", restored.resolvedModel)
        assertEquals("local", restored.resolvedProvider)
        assertEquals("cloud unavailable", restored.fallbackReason)
    }

    @Test
    fun sensitiveTargetsAndToolArgumentsAreRedactedAtStorageBoundary() {
        val file = File.createTempFile("agent-traces-redacted", ".json").apply { deleteOnExit() }
        val store = AgentTraceStore(file)
        val unsafe = trace("unsafe-target-trace").copy(
            steps = listOf(
                AgentStep(
                    stepIndex = 0,
                    type = AgentStepType.TOOL,
                    name = "tool_safe_read",
                    inputParams = mapOf(
                        "token" to "private-token-value-123",
                        "query" to "contact user@example.com",
                    ),
                    outputSummary = "Bearer output-secret-value",
                    durationMs = -7L,
                ),
            ),
            model = "api_key=model-secret-value",
            provider = "Bearer provider-secret-value",
            requestedModel = "account@example.com",
            requestedProvider = "token=target-secret-value",
            resolvedModel = "/data/user/0/private/model",
            resolvedProvider = "local",
            fallbackReason = "password=fallback-secret-value",
            estimatedOutputTokens = -3,
            firstVisibleTokenLatencyMs = -5L,
        )

        store.save(listOf(unsafe))

        val serialized = file.readText()
        listOf(
            "model-secret-value",
            "provider-secret-value",
            "account@example.com",
            "target-secret-value",
            "fallback-secret-value",
            "private-token-value-123",
            "user@example.com",
            "output-secret-value",
        ).forEach { assertFalse(serialized.contains(it)) }

        val restored = store.load().single()
        assertTrue(restored.model.orEmpty().contains("[KEY_REDACTED]"))
        assertTrue(restored.provider.orEmpty().contains("[KEY_REDACTED]"))
        assertTrue(restored.requestedModel.orEmpty().contains("[EMAIL_REDACTED]"))
        assertTrue(restored.requestedProvider.orEmpty().contains("[SECRET_REDACTED]"))
        assertTrue(restored.resolvedModel.orEmpty().contains("[PATH_REDACTED]"))
        assertTrue(restored.fallbackReason.orEmpty().contains("[SECRET_REDACTED]"))
        assertEquals("[SECRET_REDACTED]", restored.steps.single().inputParams["token"])
        assertTrue(restored.steps.single().inputParams.getValue("query").contains("[EMAIL_REDACTED]"))
        assertTrue(restored.steps.single().outputSummary.contains("[KEY_REDACTED]"))
        assertEquals(0L, restored.steps.single().durationMs)
        assertEquals(0, restored.estimatedOutputTokens)
        assertEquals(0L, restored.firstVisibleTokenLatencyMs)

        file.writeText(JSONArray().put(AgentTraceStore.traceToJson(unsafe)).toString())
        val restoredLegacy = store.load().single()
        assertFalse(restoredLegacy.model.orEmpty().contains("model-secret-value"))
        assertFalse(restoredLegacy.steps.single().inputParams.getValue("token").contains("private-token-value"))
    }
}
