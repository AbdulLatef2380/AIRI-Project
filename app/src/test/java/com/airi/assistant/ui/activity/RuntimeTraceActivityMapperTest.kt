package com.airi.assistant.ui.activity

import com.airi.assistant.core.UniversalRuntimeTraceRecorder
import com.airi.assistant.core.UniversalTraceEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeTraceActivityMapperTest {
    @Test
    fun runtimeModelMilestoneBecomesOwnedActivityWithoutRawPrompt() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin(
            executionId = "runtime-run-1",
            sessionId = "session-1",
            modelId = "model-1",
            providerId = "provider-1",
            executionMode = "cloud",
            input = "private prompt must never enter the activity feed",
            tools = emptyList(),
        )
        val modelContext = recorder.eventsFor("runtime-run-1")
            .first { it.eventType == UniversalTraceEventType.MODEL_CONTEXT_RESOLVED }

        val activity = RuntimeTraceActivityMapper.map(modelContext)

        assertNotNull(activity)
        assertEquals("runtime-run-1", activity?.executionId)
        assertEquals(ActivityCategory.MODEL, activity?.category)
        assertEquals("runtime_model_context_resolved", activity?.machineTag)
        assertFalse(activity?.message.orEmpty().contains("private prompt"))
        assertFalse(activity?.detail.orEmpty().contains("private prompt"))
        assertEquals(ActivityPhase.MODEL, activity?.metadata?.phase)
        assertEquals(ActivityStatus.COMPLETED, activity?.metadata?.status)
        assertEquals(ActivityVisibility.USER_VISIBLE, activity?.metadata?.visibility)
    }

    @Test
    fun admissionEventsNeverBecomeUserActivityRows() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin(
            executionId = "runtime-run-2",
            sessionId = "session-2",
            modelId = "model-2",
            providerId = "provider-2",
            executionMode = "local",
            input = "safe input",
            tools = emptyList(),
        )

        val events = recorder.eventsFor("runtime-run-2")
        assertTrue(events.any { it.eventType == UniversalTraceEventType.CAPABILITY_FILTERED })
        assertTrue(events.any { it.eventType == UniversalTraceEventType.TOOLS_EXPOSED })
        assertTrue(events.any { it.eventType == UniversalTraceEventType.REQUEST_RECEIVED })
        events.filter {
            it.eventType in setOf(
                UniversalTraceEventType.REQUEST_RECEIVED,
                UniversalTraceEventType.CAPABILITY_FILTERED,
                UniversalTraceEventType.TOOLS_EXPOSED,
            )
        }.forEach { event ->
            assertEquals(null, RuntimeTraceActivityMapper.map(event))
        }
    }

    @Test
    fun modelResponseAndParserDiagnosticsNeverBecomeUserActivityRows() {
        val recorder = UniversalRuntimeTraceRecorder()
        val response = recorder.modelResponse(
            executionId = "runtime-private-response",
            sessionId = "session-private-response",
            response = "private model text must never enter the user activity feed",
            containsToolCallCandidate = true,
            parserInputClassification = "tool_call_candidate",
        )

        assertEquals(UniversalTraceEventType.MODEL_RESPONSE_RECEIVED, response.eventType)
        assertNull(RuntimeTraceActivityMapper.map(response))
    }

    @Test
    fun modelRequestBuiltRemainsDiagnosticOnly() {
        val recorder = UniversalRuntimeTraceRecorder()
        recorder.begin(
            executionId = "runtime-model-request",
            sessionId = "session-1",
            modelId = "model-1",
            providerId = "provider-1",
            executionMode = "cloud",
            input = "private prompt",
            tools = emptyList(),
        )
        recorder.modelRequestBuilt(
            executionId = "runtime-model-request",
            sessionId = "session-1",
            promptTokens = 12,
            historyTurns = 1,
            toolCount = 0,
            requiresToolCalling = false,
            requiresLongContext = false,
        )
        val event = recorder.eventsFor("runtime-model-request").first {
            it.eventType == UniversalTraceEventType.MODEL_REQUEST_BUILT
        }

        assertNull(RuntimeTraceActivityMapper.map(event))
    }
}
