package com.airi.assistant.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStatusBusTest {

    @Test
    fun graphStartPreservesOwningSession() {
        ExecutionStatusBus.onGraphStarted(
            goalDescription = "trace contract",
            totalNodes = 1,
            executionId = "execution-session-bound",
            sessionId = "session-a",
        )
        assertEquals("session-a", ExecutionStatusBus.status.value.sessionId)
        ExecutionStatusBus.reset()
    }

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
}
