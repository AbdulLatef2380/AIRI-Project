package com.airi.assistant.core

import com.airi.assistant.agent.loop.tool.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalRuntimeTraceTest {

    @Test
    fun recordsOrderedIntentAdmissionStages() {
        val recorder = UniversalRuntimeTraceRecorder()
        val executionId = "execution-1"
        val sessionId = "session-1"
        recorder.begin(executionId, sessionId, "model", "provider", "test", "hello", emptyList())
        recorder.intentStage(executionId, sessionId, "INTENT_RAW", "received")
        recorder.intentStage(executionId, sessionId, "INTENT_RESOLVED", "resolved")
        recorder.intentStage(executionId, sessionId, "TOOL_ADMITTED", "admitted", "web_search")

        val stages = recorder.events
            .filter { it.eventType == UniversalTraceEventType.INTENT_STAGE_RECORDED }
            .map { it.attributes["stage"] }
        assertEquals(listOf("INTENT_RAW", "INTENT_RESOLVED", "TOOL_ADMITTED"), stages)
    }

    @Test
    fun begin_recordsGenericCapabilitySnapshotAndToolExposure() {
        val recorder = UniversalRuntimeTraceRecorder()
        val tool = ToolSchema(
            name = "connector_read",
            description = "Read a connector resource",
            parameters = mapOf("id" to ToolSchema.Param("string"))
        )

        val traceId = recorder.begin(
            executionId = "exec-1",
            sessionId = "session-1",
            modelId = "model-1",
            providerId = "provider-1",
            executionMode = "cloud",
            input = "read safely",
            tools = listOf(tool),
        )

        assertTrue(traceId.isNotBlank())
        val snapshot = recorder.events.first { it.eventType == UniversalTraceEventType.CAPABILITY_SNAPSHOT_RESOLVED }
        assertEquals(true, snapshot.capabilities.single().exists)
        assertEquals(true, snapshot.capabilities.single().registered)
        assertEquals(true, snapshot.capabilities.single().exposed)
        assertEquals(null, snapshot.capabilities.single().connected)
        assertTrue(recorder.events.any { it.eventType == UniversalTraceEventType.TOOLS_EXPOSED })
        assertTrue(recorder.events.any {
            it.eventType == UniversalTraceEventType.CAPABILITY_STAGE_RECORDED &&
                it.attributes["stage"] == CapabilityRuntimeStage.EXPOSED_TO_MODEL.name
        })
    }

    @Test
    fun beginPreservesCallerTraceIdForCrossLayerCorrelation() {
        val recorder = UniversalRuntimeTraceRecorder()
        val traceId = recorder.begin(
            executionId = "exec-correlated",
            sessionId = "session-correlated",
            modelId = "model",
            providerId = "provider",
            executionMode = "test",
            input = "hello",
            tools = emptyList(),
            requestedTraceId = "trace-correlated",
        )

        assertEquals("trace-correlated", traceId)
        assertTrue(recorder.events.all { it.traceId == "trace-correlated" })
        assertTrue(recorder.events.all { it.executionId == "exec-correlated" })
        assertTrue(recorder.events.all { it.sessionId == "session-correlated" })
    }

    @Test
    fun modelResponseStoresMetadataAndRedactsSecrets() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin("exec-1", "", "model", "provider", "cloud", "input", emptyList())
        recorder.modelResponse(
            executionId = "exec-1",
            sessionId = "",
            response = "Bearer secret-token user@example.com tool_call",
            containsToolCallCandidate = true,
            parserInputClassification = "agent_step",
        )

        val event = recorder.events.last { it.eventType == UniversalTraceEventType.MODEL_RESPONSE_RECEIVED }
        val preview = event.attributes.getValue("redactedPreview")
        assertFalse(preview.contains("secret-token"))
        assertFalse(preview.contains("user@example.com"))
        assertEquals("true", event.attributes.getValue("containsToolCallCandidate"))
        assertTrue(event.attributes.getValue("sha256").matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun modelRequestBuiltStoresOnlyBoundedRequestMetadata() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin("exec-1", "session-1", "model", "provider", "cloud", "private input", emptyList())
        recorder.modelRequestBuilt(
            executionId = "exec-1",
            sessionId = "session-1",
            modelId = "gemini-2.0-flash",
            providerId = "google",
            promptTokens = 120,
            historyTurns = 4,
            toolCount = 3,
            requiresToolCalling = true,
            requiresLongContext = false,
        )

        val event = recorder.events.last { it.eventType == UniversalTraceEventType.MODEL_REQUEST_BUILT }
        assertEquals("exec-1", event.executionId)
        assertEquals("session-1", event.sessionId)
        assertEquals("AgentLoop", event.component)
        assertEquals("built", event.outcome)
        assertEquals("gemini-2.0-flash", event.attributes["model"])
        assertEquals("google", event.attributes["provider"])
        assertEquals("gemini-2.0-flash", event.attributes["requestedModel"])
        assertEquals("google", event.attributes["requestedProvider"])
        assertEquals("120", event.attributes["promptTokens"])
        assertEquals("4", event.attributes["historyTurns"])
        assertEquals("3", event.attributes["toolCount"])
        assertEquals("true", event.attributes["requiresToolCalling"])
        assertFalse(event.attributes.values.any { it.contains("private input") })
    }

    @Test
    fun modelTargetMetadataIsBoundedRedactedAndCarriesResolvedFallbackEvidence() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin("exec-target", "session-target", "requested-model", "requested-provider", "cloud", "private", emptyList())
        recorder.modelRequestBuilt(
            executionId = "exec-target",
            sessionId = "session-target",
            modelId = "m".repeat(200),
            providerId = "api_key=provider-secret",
            promptTokens = -1,
            historyTurns = -1,
            toolCount = -1,
            requiresToolCalling = false,
            requiresLongContext = false,
        )
        recorder.modelResponse(
            executionId = "exec-target",
            sessionId = "session-target",
            response = "Bearer response-secret user@example.com final answer",
            containsToolCallCandidate = false,
            parserInputClassification = "single_pass",
            durationMs = -25L,
            requestedModelId = "m".repeat(200),
            requestedProviderId = "api_key=provider-secret",
            resolvedModelId = "local-model-v2",
            resolvedProviderId = "local",
            fallbackReason = "Bearer fallback-secret provider unavailable",
        )

        val request = recorder.eventsFor("exec-target").last {
            it.eventType == UniversalTraceEventType.MODEL_REQUEST_BUILT
        }
        val response = recorder.eventsFor("exec-target").last {
            it.eventType == UniversalTraceEventType.MODEL_RESPONSE_RECEIVED
        }

        assertEquals(80, request.attributes.getValue("model").length)
        assertEquals(request.attributes["model"], request.attributes["requestedModel"])
        assertTrue(request.attributes.getValue("provider").contains("[REDACTED]"))
        assertEquals(request.attributes["provider"], request.attributes["requestedProvider"])
        assertEquals("0", request.attributes["promptTokens"])
        assertEquals("0", request.attributes["historyTurns"])
        assertEquals("0", request.attributes["toolCount"])
        assertEquals("0", response.attributes["durationMs"])
        assertEquals("agent_loop_call_llm_wall_clock", response.attributes["durationScope"])
        assertEquals("local-model-v2", response.attributes["resolvedModel"])
        assertEquals("local", response.attributes["resolvedProvider"])
        assertTrue(response.attributes.getValue("fallbackReason").contains("[REDACTED]"))
        assertTrue(response.attributes.getValue("redactedPreview").contains("[REDACTED]"))
        assertTrue(response.attributes.getValue("redactedPreview").contains("[EMAIL]"))
        val serializedAttributes = (request.attributes.values + response.attributes.values).joinToString("\n")
        assertFalse(serializedAttributes.contains("provider-secret"))
        assertFalse(serializedAttributes.contains("fallback-secret"))
        assertFalse(serializedAttributes.contains("response-secret"))
        assertFalse(serializedAttributes.contains("user@example.com"))
    }

    @Test
    fun terminalOutcomePreservesLifecycleClassificationAndRedactsReason() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin("exec-terminal", "session-1", "model", "provider", "test", "input", emptyList())

        recorder.terminalOutcome(
            executionId = "exec-terminal",
            sessionId = "session-1",
            outcome = "TIMEOUT",
            reason = "Bearer secret-token timed out",
        )

        val event = recorder.events.last()
        assertEquals(UniversalTraceEventType.EXECUTION_TERMINAL_OUTCOME, event.eventType)
        assertEquals("TIMEOUT", event.outcome)
        assertFalse(event.attributes.getValue("reason").contains("secret-token"))
    }

    @Test
    fun toolSchemaHashIsStableAndDoesNotContainDescriptionOrArguments() {
        val tool = ToolSchema(
            name = "safe_read",
            description = "private user content must not be logged",
            parameters = mapOf("query" to ToolSchema.Param("string"))
        )
        val hash = RuntimeTraceRedaction.schemaHash(tool)
        assertEquals(64, hash.length)
        assertFalse(hash.contains("private"))
        assertTrue(RuntimeTraceRedaction.schemaSize(tool) > 0)
    }
}
