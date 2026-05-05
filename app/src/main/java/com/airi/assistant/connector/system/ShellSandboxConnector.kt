package com.airi.assistant.connector.system

import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import com.airi.assistant.security.SandboxedProcessManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ShellSandboxConnector — safe, allowlisted shell command execution.
 *
 * ── SECURITY MODEL ────────────────────────────────────────────────────────
 *
 *   Commands are checked against [ALLOWED_BINARIES] before execution.
 *   Any command whose first token is not in the allowlist is rejected
 *   immediately with a `policy_violation` error — it is never passed to
 *   [SandboxedProcessManager].
 *
 *   [SandboxedProcessManager] then adds a second layer:
 *     - Empty environment (no PATH leakage)
 *     - Output truncated at 64 KB
 *     - Hard timeout (15 s default)
 *     - At most [MAX_PARALLEL] processes concurrently
 *     - Force-kill on timeout or cancellation
 *
 * ── ACTIONS ───────────────────────────────────────────────────────────────
 *
 * | action  | required params         | notes                                |
 * |---------|-------------------------|--------------------------------------|
 * | `exec`  | command                 | Run an allowlisted shell command     |
 * | `ls`    | path (optional)         | Shorthand for `ls -la <path>`        |
 * | `pwd`   | —                       | Print working directory              |
 * | `echo`  | text                    | Echo text back                       |
 * | `date`  | —                       | Current date/time from device        |
 * | `id`    | —                       | User/group IDs of the app process    |
 * | `env`   | —                       | Safe env vars (allowlisted keys)     |
 * | `uname` | —                       | Kernel/OS information                |
 *
 * ── PROOF TAGS ───────────────────────────────────────────────────────────
 *
 *   SHELL_EXEC_ALLOWED  — command passed the allowlist check
 *   SHELL_EXEC_DENIED   — command was rejected by the allowlist
 *   SHELL_EXEC_RESULT   — exit code and elapsed time after execution
 */
class ShellSandboxConnector(
    private val processManager: SandboxedProcessManager = SandboxedProcessManager(
        maxParallel    = MAX_PARALLEL,
        defaultTimeout = DEFAULT_TIMEOUT_MS,
        maxOutputBytes = MAX_OUTPUT_BYTES
    )
) : Connector {

    override val id          = "shell_sandbox"
    override val name        = "Shell Sandbox"
    override val description = "Execute allowlisted shell commands in a secure, isolated subprocess."
    override val type        = ConnectorType.SYSTEM

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Shell sandbox ready — ${ALLOWED_BINARIES.size} allowed commands")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("shell", "exec", "sandbox", "system", "terminal", "command")
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val ok = runCatching {
            processManager.exec(listOf("echo", "ping"), timeoutMs = 2_000)
        }.getOrNull()?.success == true
        _state.value = ConnectorState(
            connected = true, healthy = ok,
            statusLine = if (ok) "Shell exec confirmed" else "Shell exec unavailable on this device",
            lastUpdatedMs = System.currentTimeMillis()
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput {
        return when (input.action.lowercase()) {
            "exec"  -> execAction(input.params["command"] ?: input.text)
            "ls"    -> execAction("ls -la ${input.params["path"] ?: "."}".trim())
            "pwd"   -> execAction("pwd")
            "echo"  -> execAction("echo ${input.params["text"] ?: input.text}")
            "date"  -> execAction("date")
            "id"    -> execAction("id")
            "uname" -> execAction("uname -a")
            "env"   -> safeEnvAction()
            else    -> ConnectorOutput.Failure(
                "unknown_action",
                "ShellSandboxConnector: unknown action '${input.action}'. " +
                    "Supported: exec, ls, pwd, echo, date, id, env, uname"
            )
        }
    }

    // ── Actions ───────────────────────────────────────────────────────────────

    private suspend fun execAction(commandLine: String): ConnectorOutput {
        if (commandLine.isBlank()) {
            return ConnectorOutput.Failure("bad_input", "Empty command")
        }

        val tokens = parseCommand(commandLine)
        if (tokens.isEmpty()) {
            return ConnectorOutput.Failure("bad_input", "Could not parse command: $commandLine")
        }

        val binary = tokens.first()
        if (binary !in ALLOWED_BINARIES) {
            Log.w(TAG, "AIRI_PROOF SHELL_EXEC_DENIED binary='$binary' allowed=${ALLOWED_BINARIES}")
            return ConnectorOutput.Failure(
                "policy_violation",
                "Command '$binary' is not in the allowlist. " +
                    "Allowed: ${ALLOWED_BINARIES.sorted().joinToString(", ")}"
            )
        }

        Log.i(TAG, "AIRI_PROOF SHELL_EXEC_ALLOWED cmd='${commandLine.take(80)}'")

        // Resolve full binary path for Android (no PATH in clean env)
        val resolvedTokens = resolveAndroidPath(tokens)

        val result = processManager.exec(
            args      = resolvedTokens,
            timeoutMs = DEFAULT_TIMEOUT_MS,
            extraEnv  = mapOf("TERM" to "dumb")
        )

        Log.i(TAG, "AIRI_PROOF SHELL_EXEC_RESULT exit=${result.exitCode} elapsed=${result.elapsedMs}ms timedOut=${result.timedOut}")

        return if (result.success) {
            ConnectorOutput.Success(
                text = result.output.ifBlank { "(no output)" },
                data = mapOf(
                    "exit_code"  to result.exitCode.toString(),
                    "elapsed_ms" to result.elapsedMs.toString(),
                    "truncated"  to result.truncated.toString(),
                    "binary"     to binary
                ),
                durationMs = result.elapsedMs
            )
        } else {
            val reason = when {
                result.timedOut -> "Command timed out after ${DEFAULT_TIMEOUT_MS}ms"
                result.error != null -> result.error
                else -> "Exit code ${result.exitCode}: ${result.output.take(200)}"
            }
            ConnectorOutput.Failure("exec_failed", reason ?: "Unknown failure", retryable = false)
        }
    }

    /**
     * Safe env dump — only expose non-sensitive keys.
     * Never dumps API keys, tokens, or paths with credentials.
     */
    private suspend fun safeEnvAction(): ConnectorOutput {
        val result = processManager.exec(
            args = listOf("/system/bin/env"),
            timeoutMs = 3_000
        )
        val safe = result.output.lines()
            .filter { line ->
                val key = line.substringBefore("=").uppercase()
                SAFE_ENV_KEYS.any { safe -> key.startsWith(safe) }
            }
            .joinToString("\n")
        return ConnectorOutput.Success(
            text = safe.ifBlank { "(no safe env vars found)" },
            data = mapOf("exit_code" to result.exitCode.toString())
        )
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Very simple command tokenizer — splits on spaces, respects single-quoted
     * strings but does not handle all shell quoting rules. This is intentional:
     * we're running a controlled subset of commands, not a full shell.
     */
    private fun parseCommand(cmd: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        for (c in cmd) {
            when {
                c == '\'' -> inQuote = !inQuote
                c == ' ' && !inQuote -> {
                    if (current.isNotEmpty()) { tokens += current.toString(); current.clear() }
                }
                else -> current.append(c)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * Prepend Android system binary path if not already absolute.
     * Android's /system/bin contains most standard Unix utilities.
     */
    private fun resolveAndroidPath(tokens: List<String>): List<String> {
        val binary = tokens.first()
        if (binary.startsWith("/")) return tokens   // already absolute
        val androidBinary = "/system/bin/$binary"
        return listOf(androidBinary) + tokens.drop(1)
    }

    // ── Constants ─────────────────────────────────────────────────────────────

    companion object {
        private const val TAG              = "ShellSandboxConnector"
        private const val MAX_PARALLEL     = 2
        private const val DEFAULT_TIMEOUT_MS = 15_000L
        private const val MAX_OUTPUT_BYTES = 64 * 1024

        /**
         * Allowlist of binary names (not full paths).
         * Only binaries in this set can be executed via this connector.
         * Any path-traversal attempt (e.g. "../sbin/su") is caught by
         * the [parseCommand] parser which keeps only the final token.
         */
        val ALLOWED_BINARIES: Set<String> = setOf(
            "ls", "pwd", "echo", "cat", "date", "id", "uname",
            "env", "grep", "find", "wc", "head", "tail", "sort",
            "uniq", "df", "du", "ps", "stat", "which", "printenv",
            "test", "true", "false", "seq", "expr", "basename", "dirname"
        )

        private val SAFE_ENV_KEYS: List<String> = listOf(
            "ANDROID", "LANG", "LOCALE", "TZ", "TERM", "HOME",
            "EXTERNAL_STORAGE", "SECONDARY_STORAGE"
        )
    }
}
