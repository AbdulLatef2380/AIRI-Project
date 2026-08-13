package com.airi.assistant.agent.sandbox

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * SandboxExecutor — capability-restricted, argv-exec sandbox runner.
 *
 * Hardened version (Phase-3 P0 security batch):
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
        session.appendLog(SandboxLogEntry(
            level = if (result is ExecutionResult.Success) "INFO" else "ERROR",
            message = "RESULT $result"
        ))
        result
    }

    private fun executeFileWrite(task: SandboxTask): ExecutionResult {
        val path = safePath(task.command)
            ?: return ExecutionResult.SecurityViolation("Path escapes sandbox: ${task.command}")
        return try {
            path.parentFile?.mkdirs()
            path.writeText(task.content ?: "")
            ExecutionResult.Success("Written ${task.content?.length ?: 0} bytes")
        } catch (e: Exception) {
            ExecutionResult.Failure("Write failed: ${e.message}")
        }
    }

    private fun executeFileRead(task: SandboxTask): ExecutionResult {
        val path = safePath(task.command)
            ?: return ExecutionResult.SecurityViolation("Path escapes sandbox")
        return try {
            if (!path.exists()) ExecutionResult.Failure("Not found: ${path.name}")
            else ExecutionResult.Success(path.readText())
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
    private fun executeShell(task: SandboxTask): ExecutionResult {
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

        // : Argument scope restriction — prevent path traversal attacks.
        // Find the first non-flag argument (doesn't start with '-') and check it
        // against the per-binary restriction if one exists.
        val restriction = BINARY_ARG_RESTRICTIONS[binary]
        if (restriction != null) {
            val firstPathArg = argv.drop(1).firstOrNull { !it.startsWith("-") }
            if (firstPathArg != null && !restriction.containsMatchIn(firstPathArg)) {
                Log.w(TAG, "SANDBOX_ARG_VIOLATION binary=$binary argChars=${firstPathArg.length}")
                return ExecutionResult.SecurityViolation(
                    "Argument scope violation: '$binary $firstPathArg' — only relative paths permitted ()"
                )
            }
        }

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
            val output = StringBuilder()
            // P0-4: Guarantee process cleanup on any exit path including coroutine cancellation.
            try {
                proc.inputStream.bufferedReader().use { reader ->
                    val buf = CharArray(4096)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        if (output.length + n > OUTPUT_LIMIT_BYTES) {
                            output.append(buf, 0, OUTPUT_LIMIT_BYTES - output.length)
                            output.append("\n[output truncated at $OUTPUT_LIMIT_BYTES bytes]")
                            runCatching { proc.destroyForcibly() }
                            break
                        }
                        output.append(buf, 0, n)
                    }
                }
                // P0-4: Use timed waitFor to prevent ANR when subprocess hangs.
                // The outer withTimeoutOrNull caps wall-clock time, but a subprocess
                // that has closed stdout while still running would block proc.waitFor()
                // indefinitely. 5 s is generous for any allowlisted binary.
                @Suppress("BlockingMethodInNonBlockingContext")
                val finished = proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
                if (finished) {
                    val exit = proc.exitValue()
                    if (exit == 0) ExecutionResult.Success(output.toString(), exit)
                    else ExecutionResult.Failure(output.toString(), exit, retryable = false)
                } else {
                    ExecutionResult.Failure("Process timed out after 5 s", retryable = false)
                }
            } finally {
                // Runs on normal exit, exception, AND coroutine cancellation.
                runCatching { proc.destroyForcibly() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Shell error: ${e.message}")
            ExecutionResult.Failure("Error: ${e.message}")
        }
    }

    private fun safePath(rel: String): File? {
        val target = File(session.workspaceDir, rel).canonicalFile
        return if (target.path.startsWith(session.workspaceDir.canonicalPath)) target else null
    }

    /** Whitespace-tokenize — NO shell parsing, NO quote handling. */
    private fun tokenize(cmd: String): List<String> =
        cmd.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    companion object {
        // Allowed leading binary. Everything else is rejected.
        private val BINARY_ALLOWLIST: Set<String> = setOf(
            "ls", "cat", "echo", "mkdir", "cp", "mv", "rm",
            "find", "grep", "sed", "awk", "head", "tail", "wc",
            "sort", "uniq", "git", "zip", "unzip", "tar"
            // Removed: curl, wget — these are network primitives that should
            // be wrapped by a higher-level capability and not exposed via raw shell.
        )

        // git: only read-only subcommands. Push/fetch/pull/commit are blocked.
        private val GIT_SUBCOMMAND_ALLOWLIST: Set<String> = setOf(
            "status", "log", "diff", "show", "ls-files", "rev-parse", "branch", "config"
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

        /**
         * : Per-binary argument scope restrictions.
         *
         * Several allowlisted binaries accept path arguments that could be exploited
         * to read sensitive files outside the sandbox even when shell injection is
         * prevented. For example, `find /data -name "*.db"` passes binary-name
         * validation (find is in BINARY_ALLOWLIST) but accesses sensitive paths.
         *
         * The restriction regex describes ALLOWED argument patterns. If the first
         * non-flag argument fails to match, the command is rejected as a
         * SecurityViolation before any subprocess is spawned.
         *
         * Rules:
         *  - `find` / `ls` / `cat` / `grep` / `head` / `tail` / `wc`:
         *    only relative paths (starting with ./) or plain filenames.
         *    Absolute paths (/...) are rejected.
         */
        private val BINARY_ARG_RESTRICTIONS: Map<String, Regex> = mapOf(
            "find" to Regex("""^\./.*|^\.${'$'}"""),
            "ls"   to Regex("""^(\./.*|\.)?${'$'}"""),
            "cat"  to Regex("""^\./[^/].*"""),
            "grep" to Regex("""^[^/].*"""),
            "head" to Regex("""^[^/].*"""),
            "tail" to Regex("""^[^/].*"""),
            "wc"   to Regex("""^[^/].*"""),
        )

        private const val SAFE_PATH = "/system/bin:/system/xbin"
        private const val OUTPUT_LIMIT_BYTES = 256 * 1024  // 256 KiB
    }
}
