package com.airi.assistant.agent.sandbox

import android.util.Log
import com.airi.assistant.BuildConfig
import com.airi.assistant.security.CommandRedactor
import com.airi.assistant.domain.terminal.TerminalExecutionPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * SandboxExecutor — capability-restricted, argv-exec sandbox runner.
 *
 * Hardened version ( security batch):
 *
 *   • The allowlist is now matched on the *first token* of the command after
 *     splitting on whitespace, NOT on `startsWith` — this prevents bypasses
 *     such as `lsof` matching `ls`, or `cathy.txt` matching `cat`.
 *   • Commands are executed via `ProcessBuilder(argv)` (no `/system/bin/sh -c`).
 *     This eliminates shell-injection via `;`, `&&`, backticks, `$(...)`,
 *     `>`, `<`, `|`, globs, and quoted-arg fragmentation.
 *   • The environment is fully scrubbed (only PATH and LANG kept) before any
 *     caller-supplied entries. Caller env is restricted to a small key
 *     allowlist to prevent LD_PRELOAD / LD_LIBRARY_PATH attacks.
 *   • Output is capped to OUTPUT_LIMIT_BYTES to prevent OOM from a runaway
 *     subprocess.
 *   • Process tree is best-effort terminated on timeout.
 *
 * Behavioural compatibility:
 *   - The public surface (TaskType, SandboxTask, ExecutionResult, execute()) is
 *     unchanged. Existing callers in TerminalRuntime / SandboxManager /
 *     SkillRuntime / SandboxWorkspaceScreen continue to compile and run.
 *   - Compound binaries previously matched as "git clone" / "git status" /
 *     "git log" are now expressed as the binary `git` plus an argv whitelist.
 *     A bare `git push` is rejected (write op) — same effective policy.
 */
class SandboxExecutor(private val session: SandboxSession) {
    private val TAG = "SandboxExecutor"

    enum class TaskType { KOTLIN_SCRIPT, PYTHON_SCRIPT, SHELL_COMMAND, FILE_WRITE, FILE_READ }

    data class SandboxTask(
        val type: TaskType,
        val command: String,
        val content: String? = null,
        val timeoutMs: Long = 30_000L,
        val env: Map<String, String> = emptyMap()
    )

    sealed class ExecutionResult {
        data class Success(val output: String, val exitCode: Int = 0) : ExecutionResult()
        data class Failure(val error: String, val exitCode: Int = -1, val retryable: Boolean = false) : ExecutionResult()
        object Timeout : ExecutionResult()
        object UnsupportedOnDevice : ExecutionResult()
        data class SecurityViolation(val reason: String) : ExecutionResult()
    }

    suspend fun execute(task: SandboxTask, permit: ShellExecutionPermit? = null): ExecutionResult = withContext(Dispatchers.IO) {
        if (task.type == TaskType.SHELL_COMMAND) {
            if (permit == null) {
                return@withContext ExecutionResult.SecurityViolation(
                    "SHELL_COMMAND requires a gateway or authorized tool execution permit"
                )
            }
            if (!SandboxIsolationPolicy.mayExecuteShell(BuildConfig.DEBUG)) {
                return@withContext ExecutionResult.SecurityViolation(SandboxIsolationPolicy.RELEASE_BLOCK_REASON)
            }
            return@withContext executeApprovedShell(task)
        }
        if (task.timeoutMs <= 0L) return@withContext ExecutionResult.SecurityViolation("Timeout must be positive")
        session.appendLog(SandboxLogEntry(message = "EXEC ${task.type} → ${CommandRedactor.redact(task.command).take(80)}"))
        val result = withTimeoutOrNull(task.timeoutMs.coerceAtMost(MAX_TIMEOUT_MS)) {
            when (task.type) {
                TaskType.FILE_WRITE    -> executeFileWrite(task)
                TaskType.FILE_READ     -> executeFileRead(task)
                TaskType.SHELL_COMMAND -> executeShell(task)
                TaskType.KOTLIN_SCRIPT -> ExecutionResult.UnsupportedOnDevice
                TaskType.PYTHON_SCRIPT -> ExecutionResult.UnsupportedOnDevice
            }
        } ?: ExecutionResult.Timeout
        session.appendLog(SandboxLogEntry(
            level = if (result is ExecutionResult.Success) "INFO" else "ERROR",
            // Never put command output in the activity log; it may contain user data.
            message = "RESULT type=${result::class.simpleName}"
        ))
        result
    }

    /** Execute a shell task after the owning policy layer has issued a permit. */
    internal suspend fun executeApprovedShell(task: SandboxTask): ExecutionResult = withContext(Dispatchers.IO) {
        if (!SandboxIsolationPolicy.mayExecuteShell(BuildConfig.DEBUG)) {
            return@withContext ExecutionResult.SecurityViolation(SandboxIsolationPolicy.RELEASE_BLOCK_REASON)
        }
        if (task.type != TaskType.SHELL_COMMAND) {
            return@withContext ExecutionResult.SecurityViolation("Approved shell entry point requires SHELL_COMMAND")
        }
        if (task.timeoutMs <= 0L) return@withContext ExecutionResult.SecurityViolation("Timeout must be positive")
        session.appendLog(SandboxLogEntry(message = "EXEC ${task.type} → ${CommandRedactor.redact(task.command).take(80)}"))
        val result = withTimeoutOrNull(task.timeoutMs.coerceAtMost(MAX_TIMEOUT_MS)) {
            executeShell(task)
        } ?: ExecutionResult.Timeout
        session.appendLog(SandboxLogEntry(
            level = if (result is ExecutionResult.Success) "INFO" else "ERROR",
            message = "RESULT type=${result::class.simpleName}"
        ))
        result
    }

    private fun executeFileWrite(task: SandboxTask): ExecutionResult {
        val path = safePath(task.command)
            ?: return ExecutionResult.SecurityViolation("Path escapes sandbox: ${task.command}")
        val content = task.content ?: ""
        val bytes = content.toByteArray(Charsets.UTF_8)
        if (bytes.size > MAX_FILE_BYTES) {
            return ExecutionResult.SecurityViolation("File exceeds ${MAX_FILE_BYTES} byte write limit")
        }
        return try {
            path.parentFile?.mkdirs()
            val temp = File(path.parentFile, ".${path.name}.tmp-${System.nanoTime()}")
            temp.outputStream().use { it.write(bytes) }
            if (!temp.renameTo(path)) {
                temp.delete()
                return ExecutionResult.Failure("Atomic rename failed")
            }
            ExecutionResult.Success("Written ${bytes.size} bytes")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExecutionResult.Failure("Write failed: ${e.message}")
        }
    }

    private fun executeFileRead(task: SandboxTask): ExecutionResult {
        val path = safePath(task.command)
            ?: return ExecutionResult.SecurityViolation("Path escapes sandbox")
        return try {
            if (!path.exists()) {
                ExecutionResult.Failure("Not found: ${path.name}")
            } else if (path.length() > MAX_FILE_BYTES) {
                ExecutionResult.SecurityViolation("File exceeds ${MAX_FILE_BYTES} byte read limit")
            } else {
                ExecutionResult.Success(path.inputStream().use { it.readBytes() }.toString(Charsets.UTF_8))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ExecutionResult.Failure("Read failed: ${e.message}")
        }
    }

    /**
     * Argv-style shell execution.
     *
     * Steps:
     *   1. Tokenize command (no shell interpretation).
     *   2. Reject any token containing shell metacharacters.
     *   3. Verify argv[0] is in BINARY_ALLOWLIST.
     *   4. For "git", verify argv[1] is in GIT_SUBCOMMAND_ALLOWLIST.
     *   5. Build a scrubbed env map.
     *   6. Exec with ProcessBuilder(argv), redirectErrorStream=true.
     *   7. Cap output, force-kill process tree on timeout.
     */
    private suspend fun executeShell(task: SandboxTask): ExecutionResult {
        val policy = TerminalExecutionPolicy.evaluate(task.command)
        if (!policy.allowed) return ExecutionResult.SecurityViolation(policy.reason)
        val argv = tokenize(task.command)
        if (argv.isEmpty()) {
            return ExecutionResult.SecurityViolation("Empty command")
        }
        // Reject any token containing shell metacharacters.
        for (tok in argv) {
            if (META_CHARS.any { it in tok }) {
                return ExecutionResult.SecurityViolation(
                    "Shell metacharacter in argv: ${tok.take(40)}"
                )
            }
        }
        val binary = argv[0]
        if (binary !in BINARY_ALLOWLIST) {
            return ExecutionResult.SecurityViolation("Binary not in allowlist: $binary")
        }
        // git: only read-only subcommands
        if (binary == "git") {
            val sub = argv.getOrNull(1)
            if (sub == null || sub !in GIT_SUBCOMMAND_ALLOWLIST) {
                return ExecutionResult.SecurityViolation(
                    "git subcommand not allowed: ${sub ?: "<missing>"}"
                )
            }
        }

        validateArguments(binary, argv)?.let { return ExecutionResult.SecurityViolation(it) }

        return try {
            val pb = ProcessBuilder(argv)
                .directory(session.workspaceDir)
                .redirectErrorStream(true)

            // Scrubbed environment.
            val env = pb.environment()
            env.clear()
            env["PATH"] = SAFE_PATH
            env["LANG"] = "C"
            // Caller env: only keys that match a strict whitelist.
            for ((k, v) in task.env) {
                if (k in CALLER_ENV_ALLOWLIST && META_CHARS.none { it in v }) {
                    env[k] = v
                }
            }

            val proc = pb.start()
            // Terminal is deliberately non-interactive. Closing stdin prevents
            // commands such as `cat` from waiting forever for a user input.
            runCatching { proc.outputStream.close() }
            // Do not perform a blocking read on a pipe/FIFO here. Some Android
            // and JVM implementations do not unblock that read when the
            // coroutine is cancelled, which can pin the Gradle test worker for
            // the whole job timeout. Polling available() keeps cancellation
            // responsive; the finally block still owns process cleanup.
            return withContext(Dispatchers.IO) {
                val output = ByteArrayOutputStream()
                // P0-4: Guarantee process cleanup on any exit path including coroutine cancellation.
                try {
                    proc.inputStream.use { input ->
                        val buf = ByteArray(4096)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val available = input.available()
                            if (available == 0) {
                                if (!proc.isAlive) break
                                delay(10L)
                                continue
                            }
                            val n = input.read(buf, 0, available.coerceAtMost(buf.size))
                            if (n <= 0) continue
                            val remaining = OUTPUT_LIMIT_BYTES - output.size()
                            if (n > remaining) {
                                if (remaining > 0) output.write(buf, 0, remaining)
                                output.write("\n[output truncated at $OUTPUT_LIMIT_BYTES bytes]".toByteArray(Charsets.UTF_8))
                                ProcessTreeTerminator.terminate(proc)
                                break
                            }
                            output.write(buf, 0, n)
                        }
                    }
                    // P0-4: Use timed waitFor to prevent ANR when subprocess hangs.
                    // runInterruptible above also makes cancellation interrupt a
                    // blocked read before this wait is reached.
                    @Suppress("BlockingMethodInNonBlockingContext")
                    val finished = proc.waitFor(1, TimeUnit.SECONDS)
                    if (finished) {
                        val exit = proc.exitValue()
                        val text = output.toByteArray().toString(Charsets.UTF_8)
                        if (exit == 0) ExecutionResult.Success(text, exit)
                        else ExecutionResult.Failure(text, exit, retryable = false)
                    } else {
                        ExecutionResult.Failure("Process exceeded the bounded wait deadline", retryable = false)
                    }
                } finally {
                    // Runs on normal exit, exception, AND coroutine cancellation.
                    ProcessTreeTerminator.terminate(proc)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Shell execution failed type=${e.javaClass.simpleName}")
            ExecutionResult.Failure("Error: ${e.message}")
        }
    }

    private fun safePath(rel: String): File? {
        val root = session.workspaceDir.canonicalFile
        val target = File(root, rel).canonicalFile
        val rootPath = root.path
        val targetPath = target.path
        val insideRoot = targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)
        return target.takeIf { insideRoot }
    }

    /** Whitespace-tokenize — NO shell parsing, NO quote handling. */
    private fun tokenize(cmd: String): List<String> =
        cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun validateArguments(binary: String, argv: List<String>): String? {
        if (binary in setOf("mkdir", "cp", "mv", "rm", "zip", "unzip", "tar")) {
            return "Mutating or archive commands require the approved workspace edit route"
        }
        if (argv.any { it in setOf("-exec", "-execdir", "-delete") }) {
            return "Command option can escape the bounded executor"
        }
        for (token in argv.drop(1)) {
            if (token.startsWith("--output") || token.startsWith("--work-tree") || token.startsWith("--git-dir")) {
                return "Command option can escape the bounded executor"
            }
            if (token.startsWith("/")) return "Absolute paths are outside the workspace"
            if (token.split('/').any { it == ".." }) return "Path traversal is not allowed"
            if (token.contains('/') && !token.startsWith("./")) return "Only workspace-relative paths are allowed"
            if (token.startsWith("./")) {
                val root = session.workspaceDir.canonicalFile
                val candidate = File(root, token).canonicalFile
                if (candidate != root && !candidate.path.startsWith(root.path + File.separator)) {
                    return "Path escapes the workspace"
                }
            }
        }
        return null
    }

    companion object {
        // Allowed leading binary. Everything else is rejected.
        // Shared with TerminalExecutionPolicy so the two lists cannot drift apart.
        // curl and wget stay out: they are network primitives for a higher-level capability.
        private val BINARY_ALLOWLIST: Set<String> =
            com.airi.assistant.domain.terminal.TerminalExecutionPolicy.executableBinaries

        // git: only read-only subcommands. Push/fetch/pull/commit/config are blocked.
        private val GIT_SUBCOMMAND_ALLOWLIST: Set<String> = setOf(
            "status", "log", "diff", "show", "ls-files", "rev-parse", "branch"
            // "clone" intentionally removed — would touch the network and write FS
            // outside sandbox if a path arg were supplied.
        )

        // Environment keys callers may set. Anything else is dropped.
        private val CALLER_ENV_ALLOWLIST: Set<String> = setOf(
            "GIT_AUTHOR_NAME", "GIT_AUTHOR_EMAIL",
            "GIT_COMMITTER_NAME", "GIT_COMMITTER_EMAIL"
        )

        // Reject any argv token containing these.
        private val META_CHARS: Set<Char> = setOf(
            ';', '&', '|', '`', '$', '>', '<',
            '\n', '\r', '\u0000',
            '*', '?', '[', ']', '(', ')',
            '\\', '"', '\''
        )


        private const val SAFE_PATH = "/system/bin:/system/xbin"
        private const val OUTPUT_LIMIT_BYTES = 256 * 1024  // 256 KiB
        private const val MAX_FILE_BYTES = 256 * 1024
        private const val MAX_TIMEOUT_MS = 120_000L
    }
}

/**
 * Capability proving that a shell request already passed its owning policy
 * layer. The constructor is private so callers cannot forge one from a raw
 * command; only the gateway, the validated coding pipeline, and tests can
 * obtain the internal factories.
 */
class ShellExecutionPermit private constructor(private val authority: Authority) {
    private enum class Authority { GATEWAY, AUTHORIZED_TOOL, TEST }

    internal companion object {
        fun gateway(): ShellExecutionPermit = ShellExecutionPermit(Authority.GATEWAY)
        fun authorizedTool(): ShellExecutionPermit = ShellExecutionPermit(Authority.AUTHORIZED_TOOL)
        fun forTest(): ShellExecutionPermit = ShellExecutionPermit(Authority.TEST)
    }
}
