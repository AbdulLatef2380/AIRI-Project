package com.airi.assistant.ui.activity

import com.airi.assistant.core.UniversalRuntimeTraceRecorder
import com.airi.assistant.core.UniversalTraceEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
    }
}
