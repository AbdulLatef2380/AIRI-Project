package com.airi.assistant.connector.system

import android.util.Log
import com.airi.assistant.connector.Connector
import com.airi.assistant.connector.ConnectorInput
import com.airi.assistant.connector.ConnectorMeta
import com.airi.assistant.connector.ConnectorOutput
import com.airi.assistant.connector.ConnectorState
import com.airi.assistant.connector.ConnectorType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * TerminalConnector — sandboxed command execution via Android [ProcessBuilder].
 *
 * ## Android Sandbox Limitations
 * Regular Android apps run without root in the app-specific SELinux domain.
 * Commands that require root (`su`, `mount`, kernel modules) will fail.
 * Shell builtins (`cd`, `alias`, `export`) do not exist as standalone
 * executables and are also unavailable. Only executables accessible on
 * the Android PATH (`/system/bin`, `/vendor/bin`) are available.
 *
 * ## Security Model
 * - Allowlist of safe command prefixes enforced before execution.
 * - Hard timeout ([EXEC_TIMEOUT_MS]) prevents runaway processes.
 * - Output truncated at [MAX_OUTPUT_BYTES].
 * - Process is killed on timeout or cancellation.
 * - Environment is minimal (no HOME, no special vars injected).
 *
 * ## Supported actions
 * | action      | text param          | notes                                    |
 * |-------------|---------------------|------------------------------------------|
 * | `exec`      | command string      | Blocked if not on allowlist              |
 * | `which`     | binary name         | Check if a binary exists                 |
 * | `env`       | —                   | Dump process environment variables       |
 * | `uname`     | —                   | Kernel uname -a                          |
 * | `pwd`       | —                   | Working directory                        |
 */
class TerminalConnector : Connector {

    override val id          = "terminal"
    override val name        = "Terminal"
    override val description = "Execute sandboxed shell commands on the Android device."
    override val type        = ConnectorType.SYSTEM

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Sandboxed shell ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("shell", "exec", "terminal", "command", "system"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val testResult = runCatching { execRaw("echo", "ok") }.getOrDefault("error")
        val healthy = testResult.trim() == "ok"
        _state.value = ConnectorState(
            connected = true, healthy = healthy,
            statusLine = if (healthy) "Shell exec available (sandboxed)" else "Shell exec unavailable",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        when (input.action) {
            "exec"  -> execCommand(input.text)
            "which" -> which(input.text.trim())
            "env"   -> env()
            "uname" -> execSafe("uname", "-a")
            "pwd"   -> execSafe("pwd")
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "TerminalConnector: unknown action '${input.action}'",
            )
        }
    }

    private suspend fun execCommand(command: String): ConnectorOutput {
        if (command.isBlank()) {
            return ConnectorOutput.Failure(code = "bad_input", message = "Empty command")
        }
        val tokens = command.trim().split("\\s+".toRegex())
        val binary  = tokens.firstOrNull() ?: ""
        val allowed = ALLOWLIST.any { binary == it || binary.endsWith("/$it") }
        if (!allowed) {
            Log.w("AIRI_PROOF", "TERMINAL_BLOCKED cmd='${command.take(80)}' binary=$binary")
            return ConnectorOutput.Failure(
                code = "not_allowed",
                message = "Command '$binary' is not on the allowed list. Allowed: ${ALLOWLIST.joinToString(", ")}",
            )
        }
        return runSandboxed(tokens)
    }

    private suspend fun execSafe(vararg args: String): ConnectorOutput =
        runSandboxed(args.toList())

    private suspend fun which(binary: String): ConnectorOutput {
        if (binary.isBlank()) return ConnectorOutput.Failure(code = "bad_input", message = "Missing binary name")
        return runSandboxed(listOf("which", binary))
    }

    private fun env(): ConnectorOutput {
        val env = System.getenv().entries
            .sortedBy { it.key }
            .joinToString("\n") { (k, v) -> "$k=$v" }
        return ConnectorOutput.Success(text = env, data = mapOf("count" to System.getenv().size.toString()))
    }

    private suspend fun runSandboxed(args: List<String>): ConnectorOutput = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        Log.i("AIRI_PROOF", "TERMINAL_EXEC cmd='${args.joinToString(" ").take(80)}'")
        var process: Process? = null
        return@withContext withTimeoutOrNull(EXEC_TIMEOUT_MS) {
            runCatching {
                process = ProcessBuilder(args)
                    .redirectErrorStream(true)
                    .start()
                val output = StringBuilder()
                BufferedReader(InputStreamReader(process!!.inputStream)).use { reader ->
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        output.append(line).append('\n')
                        if (output.length > MAX_OUTPUT_BYTES) {
                            output.append("[output truncated]")
                            break
                        }
                    }
                }
                val exitCode = process!!.waitFor()
                val elapsed  = System.currentTimeMillis() - start
                val text     = output.toString().trimEnd()
                Log.i("AIRI_PROOF", "TERMINAL_DONE exit=$exitCode elapsed=${elapsed}ms chars=${text.length}")
                if (exitCode == 0) {
                    ConnectorOutput.Success(
                        text = text,
                        data = mapOf("exit_code" to exitCode.toString(), "elapsed_ms" to elapsed.toString()),
                        durationMs = elapsed,
                    )
                } else {
                    ConnectorOutput.Failure(
                        code = "exit_$exitCode",
                        message = "Command exited with code $exitCode:\n$text",
                        retryable = false,
                    )
                }
            }.getOrElse { t ->
                ConnectorOutput.Failure(
                    code = "exec_error",
                    message = "${t.javaClass.simpleName}: ${t.message}",
                    retryable = true,
                )
            }
        } ?: run {
            runCatching { process?.destroy() }
            Log.w("AIRI_PROOF", "TERMINAL_TIMEOUT cmd='${args.joinToString(" ").take(80)}' timeout=${EXEC_TIMEOUT_MS}ms")
            ConnectorOutput.Failure(
                code = "timeout",
                message = "Command timed out after ${EXEC_TIMEOUT_MS / 1000}s",
                retryable = false,
            )
        }
    }

    private fun execRaw(vararg args: String): String {
        val p = ProcessBuilder(args.toList()).redirectErrorStream(true).start()
        return p.inputStream.bufferedReader().readText().also { p.waitFor() }
    }

    companion object {
        private const val EXEC_TIMEOUT_MS  = 10_000L
        private const val MAX_OUTPUT_BYTES = 64 * 1024

        val ALLOWLIST: Set<String> = setOf(
            "echo", "cat", "ls", "pwd", "date", "which", "uname",
            "id", "env", "printenv", "whoami", "hostname",
            "df", "du", "free", "uptime", "ps",
            "find", "grep", "awk", "sed", "sort", "head", "tail", "wc",
            "curl", "wget", "ping",
            "tar", "gzip", "unzip",
            "sha256sum", "md5sum",
            "base64",
        )
    }
}
