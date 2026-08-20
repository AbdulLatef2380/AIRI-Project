package com.airi.assistant.execution

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionGenerationGateTest {

    @Test
    fun currentGenerationAcceptsCallbacksUntilCancelled() {
        val gate = ExecutionGenerationGate()
        val generation = gate.beginGeneration()

        assertTrue(gate.accepts(generation))
        gate.cancel()
        assertTrue(gate.isCancelled())
        assertFalse(gate.accepts(generation))
    }

    @Test
    fun newerGenerationRejectsLateCallbacksFromPreviousGeneration() {
        val gate = ExecutionGenerationGate()
        val first = gate.beginGeneration()
        val second = gate.beginGeneration()

        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }

    @Test
    fun resetCancelDoesNotRestoreOwnershipOfAnOlderGeneration() {
        val gate = ExecutionGenerationGate()
        val first = gate.beginGeneration()
        gate.cancel()
        val second = gate.beginGeneration()
        gate.resetCancel()

        assertFalse(gate.accepts(first))
        assertTrue(gate.accepts(second))
    }
}
