package com.airi.assistant.agent.sandbox

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class SandboxExecutor(private val session: SandboxSession) {
    private val TAG = "SandboxExecutor"

    enum class TaskType { KOTLIN_SCRIPT, PYTHON_SCRIPT, SHELL_COMMAND, FILE_WRITE, FILE_READ }

    data class SandboxTask(
        val type: TaskType, val command: String, val content: String? = null,
        val timeoutMs: Long = 30_000L, val env: Map<String, String> = emptyMap()
    )

    sealed class ExecutionResult {
        data class Success(val output: String, val exitCode: Int = 0) : ExecutionResult()
        data class Failure(val error: String, val exitCode: Int = -1, val retryable: Boolean = false) : ExecutionResult()
        object Timeout : ExecutionResult()
        object UnsupportedOnDevice : ExecutionResult()
        data class SecurityViolation(val reason: String) : ExecutionResult()
    }

    suspend fun execute(task: SandboxTask): ExecutionResult = withContext(Dispatchers.IO) {
        session.appendLog(SandboxLogEntry(message = "EXEC ${task.type} → ${task.command.take(80)}"))
        val result = withTimeoutOrNull(task.timeoutMs) {
            when (task.type) {
                TaskType.FILE_WRITE    -> executeFileWrite(task)
                TaskType.FILE_READ     -> executeFileRead(task)
                TaskType.SHELL_COMMAND -> executeShell(task)
                TaskType.KOTLIN_SCRIPT -> ExecutionResult.UnsupportedOnDevice
                TaskType.PYTHON_SCRIPT -> ExecutionResult.UnsupportedOnDevice
            }
        } ?: ExecutionResult.Timeout
        session.appendLog(SandboxLogEntry(level = if (result is ExecutionResult.Success) "INFO" else "ERROR", message = "RESULT $result"))
        result
    }

    private fun executeFileWrite(task: SandboxTask): ExecutionResult {
        val path = safePath(task.command) ?: return ExecutionResult.SecurityViolation("Path escapes sandbox: ${task.command}")
        return try { path.parentFile?.mkdirs(); path.writeText(task.content ?: ""); ExecutionResult.Success("Written ${task.content?.length ?: 0} bytes") }
        catch (e: Exception) { ExecutionResult.Failure("Write failed: ${e.message}") }
    }

    private fun executeFileRead(task: SandboxTask): ExecutionResult {
        val path = safePath(task.command) ?: return ExecutionResult.SecurityViolation("Path escapes sandbox")
        return try { if (!path.exists()) ExecutionResult.Failure("Not found: ${path.name}") else ExecutionResult.Success(path.readText()) }
        catch (e: Exception) { ExecutionResult.Failure("Read failed: ${e.message}") }
    }

    private fun executeShell(task: SandboxTask): ExecutionResult {
        val allowed = ALLOWED_SHELL.any { task.command.trimStart().startsWith(it) }
        if (!allowed) return ExecutionResult.SecurityViolation("Command not in allowlist: ${task.command.take(40)}")
        return try {
            val pb = ProcessBuilder("/system/bin/sh", "-c", task.command)
                .directory(session.workspaceDir).redirectErrorStream(true)
            task.env.forEach { (k, v) -> pb.environment()[k] = v }
            val proc = pb.start()
            val output = proc.inputStream.bufferedReader().readText()
            val exit = proc.waitFor()
            if (exit == 0) ExecutionResult.Success(output, exit) else ExecutionResult.Failure(output, exit, retryable = false)
        } catch (e: Exception) { Log.w(TAG, "Shell error: ${e.message}"); ExecutionResult.Failure("Error: ${e.message}") }
    }

    private fun safePath(rel: String): File? {
        val target = File(session.workspaceDir, rel).canonicalFile
        return if (target.path.startsWith(session.workspaceDir.canonicalPath)) target else null
    }

    companion object {
        private val ALLOWED_SHELL = listOf("ls","cat","echo","mkdir","cp","mv","rm","find","grep","sed","awk","head","tail","wc","sort","uniq","curl","wget","git clone","git status","git log","zip","unzip","tar")
    }
}
