package com.airi.assistant.core

import com.airi.assistant.agent.loop.tool.ToolSchema
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UniversalRuntimeTraceTest {

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
