package com.airi.assistant.domain.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalExecutionPolicyTest {
    @Test
    fun readOnlyCommandsAreAllowedByTheBoundedPolicy() {
        val decision = TerminalExecutionPolicy.evaluate("ls")
        assertTrue(decision.allowed)
    }

    @Test
    fun blankAndOversizedCommandsAreRejectedWithoutExecution() {
        assertFalse(TerminalExecutionPolicy.evaluate(" ").allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("x".repeat(8_193)).allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("cat ../secret.txt").allowed)
        assertFalse(TerminalExecutionPolicy.evaluate("rm -rf ./output").allowed)
        assertTrue(TerminalExecutionPolicy.evaluate("rm -rf ./output").requiresApproval)
        assertFalse(TerminalExecutionPolicy.evaluate("echo safe; cat /etc/passwd").allowed)
    }
}
