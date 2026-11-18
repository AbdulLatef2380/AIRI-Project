package com.airi.assistant.runtime.recovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPressurePolicyTest {
    @Test fun belowRunningLow_isIgnored() {
        assertNull(MemoryPressurePolicy.classify(9))
    }

    @Test fun runningLow_doesNotReleaseNativeMemory() {
        val decision = MemoryPressurePolicy.classify(10)!!
        assertTrue(decision.severity == MemoryPressurePolicy.Severity.LOW)
        assertFalse(decision.shouldReleaseNativeMemory)
        assertFalse(decision.shouldPruneStaleWorkspaces)
    }

    @Test fun runningCritical_releasesNativeMemory() {
        val decision = MemoryPressurePolicy.classify(15)!!
        assertTrue(decision.severity == MemoryPressurePolicy.Severity.CRITICAL)
        assertTrue(decision.shouldReleaseNativeMemory)
        assertTrue(decision.shouldPruneStaleWorkspaces)
    }

    @Test fun uiHidden_prunesStaleWorkspaces_butKeepsLiveNativeModel() {
        val decision = MemoryPressurePolicy.classify(20)!!
        assertTrue(decision.severity == MemoryPressurePolicy.Severity.BACKGROUND)
        assertFalse(decision.shouldReleaseNativeMemory)
        assertTrue(decision.shouldPruneStaleWorkspaces)
    }

    @Test fun complete_isTheStrongestReleaseSignal() {
        val decision = MemoryPressurePolicy.classify(80)!!
        assertTrue(decision.severity == MemoryPressurePolicy.Severity.COMPLETE)
        assertTrue(decision.shouldReleaseNativeMemory)
    }
}
