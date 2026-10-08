package com.airi.assistant.domain.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExecutionPolicyTest {
    @Test
    fun terminalExecutionIsDisabledBeforeIsolationProof() {
        val decision = TerminalExecutionPolicy.evaluate("ls")
        assertFalse(decision.allowed)
        assertTrue(decision.reason.contains("isolated", ignoreCase = true))
    }

    @Test
    fun blankAndOversizedCommandsAreRejectedWithoutExecution() {
        assertFalse(TerminalExecutionPolicy.evaluate(" ").allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("x".repeat(8_193)).allowed)
    }
}
