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
}
