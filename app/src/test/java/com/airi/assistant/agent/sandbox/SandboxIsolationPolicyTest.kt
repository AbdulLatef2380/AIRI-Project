package com.airi.assistant.agent.sandbox

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxIsolationPolicyTest {
    @Test
    fun policyOnlyShellIsDevelopmentOnlyUntilOsIsolationExists() {
        assertTrue(SandboxIsolationPolicy.mayExecuteShell(isDebugBuild = true))
        assertFalse(SandboxIsolationPolicy.mayExecuteShell(isDebugBuild = false))
        assertFalse(SandboxIsolationPolicy.OS_SANDBOX_AVAILABLE)
        assertTrue(SandboxIsolationPolicy.RELEASE_BLOCK_REASON.contains("OS-level process isolation"))
    }
}
