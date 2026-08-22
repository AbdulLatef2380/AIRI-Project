package com.airi.assistant.workspace

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SandboxEnforcerTest {

    @Test
    fun enforcesFilesystemBoundaries() {
        val contract = ExecutionContract(
            taskId = "task-1",
            allowedPaths = listOf("/data/user/0/com.airi/files/workspace"),
            allowNetwork = false,
            maxExecutionTimeMs = 30000L,
            allowedCommandPrefixes = listOf("git status", "python3")
        )

        assertTrue(SandboxEnforcer.evaluateFilesystemAccess(contract, "/data/user/0/com.airi/files/workspace/project/main.py"))
        assertFalse(SandboxEnforcer.evaluateFilesystemAccess(contract, "/etc/passwd"))
    }

    @Test
    fun enforcesCommandAllowlist() {
        val contract = ExecutionContract(
            taskId = "task-1",
            allowedPaths = listOf("/workspace"),
            allowNetwork = true,
            maxExecutionTimeMs = 30000L,
            allowedCommandPrefixes = listOf("git status", "python3")
        )

        assertTrue(SandboxEnforcer.evaluateCommandAccess(contract, "python3 script.py"))
        assertFalse(SandboxEnforcer.evaluateCommandAccess(contract, "rm -rf /"))
    }
}
