package com.airi.assistant.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class IsolatedProcessLimitsTest {
    @Test
    fun defaultsAreConservativeAndBounded() {
        val limits = IsolatedProcessLimits()
        assertEquals(IsolatedProcessLimits.DEFAULT_TIMEOUT_MS, limits.timeoutMs)
        assertEquals(IsolatedProcessLimits.DEFAULT_OUTPUT_BYTES, limits.maxOutputBytes)
    }

    @Test
    fun limitsCannotExceedHardCaps() {
        assertThrows(IllegalArgumentException::class.java) {
            IsolatedProcessLimits(timeoutMs = IsolatedProcessLimits.MAX_TIMEOUT_MS + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IsolatedProcessLimits(memoryBytes = IsolatedProcessLimits.MAX_MEMORY_BYTES + 1)
        }
        assertThrows(IllegalArgumentException::class.java) {
            IsolatedProcessLimits(maxOutputBytes = IsolatedProcessLimits.MAX_OUTPUT_BYTES + 1)
        }
    }
}
