package com.airi.assistant.agent.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SandboxExecutorSecurityTest {

    @Test
    fun fileReadCannotEscapeSiblingDirectory() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        val sibling = java.io.File(root.parentFile, root.name + "-sibling").apply {
            mkdirs()
            java.io.File(this, "secret.txt").writeText("private")
        }
        try {
            val session = SandboxSession("test", "test", root)
            val result = SandboxExecutor(session).execute(
                SandboxExecutor.SandboxTask(
                    type = SandboxExecutor.TaskType.FILE_READ,
                    command = "../${sibling.name}/secret.txt"
                )
            )
            assertTrue(result is SandboxExecutor.ExecutionResult.SecurityViolation)
        } finally {
            root.deleteRecursively()
            sibling.deleteRecursively()
        }
    }

    @Test
    fun fileReadRejectsOversizedOutput() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        try {
            java.io.File(root, "large.txt").writeBytes(ByteArray(256 * 1024 + 1))
            val result = SandboxExecutor(SandboxSession("test", "test", root)).execute(
                SandboxExecutor.SandboxTask(
                    type = SandboxExecutor.TaskType.FILE_READ,
                    command = "large.txt"
                )
            )
            assertTrue(result is SandboxExecutor.ExecutionResult.SecurityViolation)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun shellInjectionAbsolutePathsAndNetworkGitAreRejected() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        try {
            val executor = SandboxExecutor(SandboxSession("test", "test", root))
            val injection = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "echo safe; touch escaped")
            )
            val absoluteRead = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "cat /etc/passwd")
            )
            val networkGit = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "git clone https://example.invalid/repo")
            )
            assertTrue(injection is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(absoluteRead is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(networkGit is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(!java.io.File(root, "escaped").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
