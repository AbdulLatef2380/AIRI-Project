package com.airi.assistant.core

import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionTraceGatewayTest {
    @Test
    fun `same execution action and phase is emitted once`() {
        ExecutionTraceGateway.resetForTests()
        val events = mutableListOf<ActivityEvent>()
        val emit: (ActivityEvent) -> Unit = { events += it }

        assertTrue(ExecutionTraceGateway.emitExecution(
            executionId = "exec-1",
            actionId = "action-1",
            phase = "started",
            message = "started",
            category = ActivityCategory.TOOL,
            emit = emit,
        ))
        assertFalse(ExecutionTraceGateway.emitExecution(
            executionId = "exec-1",
            actionId = "action-1",
            phase = "started",
            message = "duplicate",
            category = ActivityCategory.TOOL,
            emit = emit,
        ))
        assertTrue(ExecutionTraceGateway.emitExecution(
            executionId = "exec-1",
            actionId = "action-1",
            phase = "completed",
            message = "completed",
            category = ActivityCategory.TOOL,
            emit = emit,
        ))

        assertEquals(listOf("started", "completed"), events.map { it.message })
        assertEquals("exec-1", events.first().executionId)
        assertEquals("action-1", events.first().metadata.actionId)
    }

    @Test
    fun `unowned UI events are not globally collapsed`() {
        ExecutionTraceGateway.resetForTests()
        var count = 0
        val emit: (ActivityEvent) -> Unit = { count++ }

        repeat(2) {
            ExecutionTraceGateway.emitExecution(
                executionId = null,
                actionId = null,
                phase = "started",
                message = "terminal",
                category = ActivityCategory.SANDBOX,
                emit = emit,
            )
        }

        assertEquals(2, count)
    }
}
