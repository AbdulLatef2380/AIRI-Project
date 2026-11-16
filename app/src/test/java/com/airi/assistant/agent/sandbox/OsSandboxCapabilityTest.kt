package com.airi.assistant.agent.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OsSandboxCapabilityTest {
    @Test
    fun incompleteProofNeverEnablesRelease() {
        val capability = OsSandboxCapability(
            isolatedProcess = true,
            uidSeparated = true,
            appFilesDenied = true,
            networkDenied = true,
            limitsEnforced = false,
            reason = "watchdog unavailable",
        )
        assertFalse(capability.isReleaseReady)
        assertFalse(SandboxIsolationPolicy.mayExecuteShell(false, capability))
    }

    @Test
    fun completeProofStillNeedsCompileTimeFuse() {
        val capability = OsSandboxCapability(
            isolatedProcess = true,
            uidSeparated = true,
            appFilesDenied = true,
            networkDenied = true,
            limitsEnforced = true,
            reason = "probe passed",
        )
        assertTrue(capability.isReleaseReady)
        assertFalse(SandboxIsolationPolicy.mayExecuteShell(false, capability))
    }
}
