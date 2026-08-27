package com.airi.assistant.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionToolTraceLifecycleTest {

    @Test
    fun lifecycle_rejectsMissingAndStaleExecutionOwners() {
        val lifecycle = ExecutionToolTraceLifecycle()
        lifecycle.begin("execution-current")

        assertFalse(lifecycle.admitStart("", "action-1"))
        assertFalse(lifecycle.admitStart("execution-stale", "action-1"))
        assertFalse(lifecycle.admitStart("execution-current", ""))
        assertTrue(lifecycle.admitStart("execution-current", "action-1"))
    }

    @Test
    fun lifecycle_admitsOneStartAndOneTerminalEventPerAction() {
        val lifecycle = ExecutionToolTraceLifecycle()
        lifecycle.begin("execution-current")

        assertTrue(lifecycle.admitStart("execution-current", "action-1"))
        assertFalse(lifecycle.admitStart("execution-current", "action-1"))
        assertTrue(lifecycle.admitTerminal("execution-current", "action-1"))
        assertFalse(lifecycle.admitTerminal("execution-current", "action-1"))
        assertFalse(lifecycle.admitTerminal("execution-current", "unknown-action"))
    }

    @Test
    fun beginningAnotherExecutionInvalidatesPreviousActionIds() {
        val lifecycle = ExecutionToolTraceLifecycle()
        lifecycle.begin("execution-old")
        assertTrue(lifecycle.admitStart("execution-old", "action-1"))

        lifecycle.begin("execution-new")

        assertFalse(lifecycle.admitTerminal("execution-old", "action-1"))
        assertTrue(lifecycle.admitStart("execution-new", "action-1"))
    }
}
