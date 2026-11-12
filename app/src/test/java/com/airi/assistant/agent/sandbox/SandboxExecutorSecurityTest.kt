package com.airi.assistant.agent.sandbox

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
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
    fun symlinkReadAndWriteCannotEscapeWorkspace() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        val outside = java.io.File(root.parentFile, "airi-secret-${root.name}").apply { writeText("private") }
        try {
            Files.createSymbolicLink(java.io.File(root, "link.txt").toPath(), outside.toPath())
            val executor = SandboxExecutor(SandboxSession("test", "test", root))
            val read = executor.execute(SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.FILE_READ, "link.txt"))
            val write = executor.execute(SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.FILE_WRITE, "link.txt", "changed"))
            assertTrue(read is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(write is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(outside.readText() == "private")
        } finally {
            root.deleteRecursively()
            outside.delete()
        }
    }

    @Test
    fun shellPathArgumentsAndDestructiveCommandsAreRejected() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        try {
            val executor = SandboxExecutor(SandboxSession("test", "test", root))
            val traversal = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "cat ../secret.txt"),
                permit = ShellExecutionPermit.forTest(),
            )
            val destructive = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "rm -rf ./output"),
                permit = ShellExecutionPermit.forTest(),
            )
            assertTrue(traversal is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(destructive is SandboxExecutor.ExecutionResult.SecurityViolation)
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
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "echo safe; touch escaped"),
                permit = ShellExecutionPermit.forTest(),
            )
            val absoluteRead = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "cat /etc/passwd"),
                permit = ShellExecutionPermit.forTest(),
            )
            val networkGit = executor.execute(
                SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, "git clone https://example.invalid/repo"),
                permit = ShellExecutionPermit.forTest(),
            )
            assertTrue(injection is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(absoluteRead is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(networkGit is SandboxExecutor.ExecutionResult.SecurityViolation)
            assertTrue(!java.io.File(root, "escaped").exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun silentProcessIsCancelledAtSandboxDeadline() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        val fifo = java.io.File(root, "blocked.pipe")
        try {
            val mkfifo = runCatching {
                ProcessBuilder("mkfifo", fifo.absolutePath).start().also { it.waitFor() }
            }.getOrNull()
            assumeTrue("mkfifo is required for the blocking-read test", mkfifo?.exitValue() == 0)

            val startedAt = System.nanoTime()
            val result = SandboxExecutor(SandboxSession("test", "test", root)).execute(
                SandboxExecutor.SandboxTask(
                    type = SandboxExecutor.TaskType.SHELL_COMMAND,
                    command = "cat ${fifo.name}",
                    timeoutMs = 250L,
                ),
                permit = ShellExecutionPermit.forTest(),
            )
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L

            assertTrue(result is SandboxExecutor.ExecutionResult.Timeout)
            assertTrue("silent process exceeded deadline: ${elapsedMs}ms", elapsedMs < 2_000L)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun activeSessionCannotBeReleasedUntilExecutionEnds() {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        val session = SandboxSession("test", "test", root)
        try {
            assertTrue(session.beginUse())
            session.release()
            assertTrue(root.exists())
            session.endUse()
            session.release()
            assertTrue(!root.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun grantedPermitDoesNotBypassArgumentAndTraversalPolicies() = runBlocking {
        val root = Files.createTempDirectory("airi-sandbox-").toFile()
        try {
            val executor = SandboxExecutor(SandboxSession("test", "test", root))
            val attacks = listOf(
                "echo safe; touch escaped",
                "cat ./../outside.txt",
                "git status --work-tree=/tmp",
                "find . -exec cat secret.txt",
                "awk BEGIN{system(1)}",
            )
            attacks.forEach { command ->
                val result = executor.execute(
                    SandboxExecutor.SandboxTask(SandboxExecutor.TaskType.SHELL_COMMAND, command),
                    permit = ShellExecutionPermit.forTest(),
                )
                assertTrue("expected security refusal: $command, got $result", result is SandboxExecutor.ExecutionResult.SecurityViolation)
            }
            assertTrue(!java.io.File(root, "escaped").exists())
        } finally {
            root.deleteRecursively()
        }
    }
}
