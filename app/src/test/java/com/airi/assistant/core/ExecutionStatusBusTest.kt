package com.airi.assistant.core

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
}
