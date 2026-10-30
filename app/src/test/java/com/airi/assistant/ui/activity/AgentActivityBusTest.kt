package com.airi.assistant.ui.activity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentActivityBusTest {

    @Test
    fun unscopedBackgroundEventIsNotAttributedToTheActiveExecution() {
        com.airi.assistant.core.ExecutionStatusBus.reset()
        AgentActivityBus.clearHistory()
        com.airi.assistant.core.ExecutionStatusBus.onGraphStarted(
            goalDescription = "Active task",
            totalNodes = 1,
            executionId = "active-run",
        )

        AgentActivityBus.emit(
            ActivityEvent(message = "background event", category = ActivityCategory.SYSTEM),
        )

        val event = AgentActivityBus.recentEvents.value.single()
        assertNull(event.executionId)
        assertTrue(event.sequenceNumber != null)
        AgentActivityBus.emit(ActivityEvent(message = "second background event", category = ActivityCategory.SYSTEM))
        val ordered = AgentActivityBus.recentEvents.value
        assertTrue(ordered[0].sequenceNumber!! > ordered[1].sequenceNumber!!)
        AgentActivityBus.clearHistory()
        com.airi.assistant.core.ExecutionStatusBus.reset()
    }

    @Test
    fun sanitizerRedactsCredentialsPathsAndPrivateUrisBeforeTraceDisplay() {
        val token = "ghp_" + "A".repeat(36)
        val original = ActivityEvent(
            id = "event-1",
            timestampMs = 123L,
            message = "Read /data/user/0/com.airi/files/token.txt with token=$token",
            detail = "Authorization: Bearer $token; password=hunter2; content://private/item",
            executionId = "execution-1",
            category = ActivityCategory.TOOL,
            severity = ActivitySeverity.INFO,
            machineTag = "tool_call",
        )

        val sanitized = sanitizeActivityEventForDisplay(original)
        val visibleText = listOfNotNull(sanitized.message, sanitized.detail).joinToString("\n")

        assertFalse(visibleText.contains(token))
        assertFalse(visibleText.contains("hunter2"))
        assertFalse(visibleText.contains("/data/user/0/com.airi/files/token.txt"))
        assertFalse(visibleText.contains("content://private/item"))
        assertTrue(visibleText.contains("[PATH_REDACTED]"))
        assertTrue(visibleText.contains("[URI_REDACTED]"))
        assertEquals(original.id, sanitized.id)
        assertEquals(original.executionId, sanitized.executionId)
        assertEquals(original.timestampMs, sanitized.timestampMs)
        assertEquals(original.category, sanitized.category)
        assertEquals(original.severity, sanitized.severity)
        assertEquals(original.machineTag, sanitized.machineTag)
    }
}
