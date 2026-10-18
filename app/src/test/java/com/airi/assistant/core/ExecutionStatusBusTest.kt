package com.airi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStatusBusTest {

    @Test
    fun eventsWithoutExplicitIdentityAreRejected() {
        assertFalse(ExecutionStatusBus.acceptsEvent("execution-a", ""))
        assertFalse(ExecutionStatusBus.acceptsEvent("", "execution-a"))
    }

    @Test
    fun eventsFromAnotherExecutionAreRejected() {
        assertFalse(ExecutionStatusBus.acceptsEvent("execution-a", "execution-b"))
        assertTrue(ExecutionStatusBus.acceptsEvent("execution-a", "execution-a"))
    }

    @Test
    fun statusFromAnotherOrUnscopedSessionIsRejected() {
        assertFalse(ExecutionStatusBus.belongsToSession("session-a", "session-b"))
        assertFalse(ExecutionStatusBus.belongsToSession("session-a", ""))
        assertFalse(ExecutionStatusBus.belongsToSession("", "session-a"))
        assertTrue(ExecutionStatusBus.belongsToSession("session-a", "session-a"))
    }

    @Test
    fun agentLoopStepLifecycleUsesOneStableActionIdAndMachineTags() {
        val executionId = "tree-integration-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Review repository", 1, executionId)
        ExecutionStatusBus.onWaveStarted(listOf("step-1"), listOf("read_file"), executionId)
        ExecutionStatusBus.onToolStarted(executionId, "tool-1", "read_file")
        ExecutionStatusBus.onToolCompleted(executionId, "tool-1", "read_file", 12L)
        ExecutionStatusBus.onNodeCompleted("step-1", 1, executionId)

        val stepEvents = ExecutionStatusBus.trace.value.filter {
            it.kind == ExecutionTraceKind.STEP_STARTED || it.kind == ExecutionTraceKind.STEP_COMPLETED
        }
        assertEquals(listOf("step-1", "step-1"), stepEvents.map { it.actionId })
        assertEquals("step_started", stepEvents[0].machineTag)
        assertEquals("step_completed", stepEvents[1].machineTag)
        assertEquals("tool_call", ExecutionStatusBus.trace.value.first { it.actionId == "tool-1" }.machineTag)
        ExecutionStatusBus.reset()
    }
}
