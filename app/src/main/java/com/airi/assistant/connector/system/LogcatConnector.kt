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
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * LogcatConnector — reads the Android system log for observability and debug.
 *
 * This connector lets the agent inspect its own runtime log to diagnose
 * issues, verify AIRI_PROOF events, and answer user questions about
 * what the system did.
 *
 * ## Safety
 * - Runs `logcat -d` (dump and exit — never a persistent process).
 * - Applies a hard line limit ([MAX_LINES]) to prevent memory pressure.
 * - AIRI_PROOF filter action is the preferred path: returns only
 *   structured audit events, not raw system noise.
 *
 * ## Supported actions
 * | action              | params                          | notes                          |
 * |---------------------|---------------------------------|--------------------------------|
 * | `read_recent`       | `lines` (optional, default 100) | Last N lines, all tags         |
 * | `read_airi_proof`   | `lines` (optional, default 50)  | AIRI_PROOF tag only            |
 * | `read_filtered`     | `tag`, `lines`                  | Specific logcat tag            |
 * | `read_errors`       | `lines` (optional)              | E/ level lines only            |
 */
class LogcatConnector : Connector {

    override val id          = "logcat"
    override val name        = "Logcat"
    override val description = "Read Android system log for runtime observability."
    override val type        = ConnectorType.SYSTEM

    private val _state = MutableStateFlow(
        ConnectorState(connected = true, healthy = true, statusLine = "Ready")
    )

    override fun meta() = ConnectorMeta(
        id = id, name = name, description = description, type = type,
        tags = listOf("log", "debug", "airi_proof", "observability", "system"),
    )

    override fun state(): StateFlow<ConnectorState> = _state.asStateFlow()

    override suspend fun connect(): ConnectorState {
        val canRead = runCatching { runLogcat(listOf("-d", "-t", "1")).isNotEmpty() }.getOrDefault(false)
        _state.value = ConnectorState(
            connected = true, healthy = canRead,
            statusLine = if (canRead) "Logcat accessible" else "Logcat read returned empty",
            lastUpdatedMs = System.currentTimeMillis(),
        )
        return _state.value
    }

    override suspend fun disconnect() { /* always-on */ }

    override suspend fun execute(input: ConnectorInput): ConnectorOutput = withContext(Dispatchers.IO) {
        val lines = input.params["lines"]?.toIntOrNull()?.coerceIn(1, MAX_LINES) ?: DEFAULT_LINES
        when (input.action) {
            "read_recent"     -> readRecent(lines)
            "read_airi_proof" -> readFiltered(tag = "AIRI_PROOF", lines = lines)
            "read_filtered"   -> {
                val tag = input.params["tag"].orEmpty()
                if (tag.isBlank()) ConnectorOutput.Failure(code = "bad_input", message = "Missing 'tag' param")
                else readFiltered(tag = tag, lines = lines)
            }
            "read_errors"     -> readErrors(lines)
            else -> ConnectorOutput.Failure(
                code = "unknown_action",
                message = "LogcatConnector: unknown action '${input.action}'",
            )
        }
    }

    private fun readRecent(lines: Int): ConnectorOutput {
        val args = listOf("-d", "-t", lines.toString())
        return execute(args, "read_recent", lines)
    }

    private fun readFiltered(tag: String, lines: Int): ConnectorOutput {
        val args = listOf("-d", "-t", lines.toString(), "-s", "$tag:V")
        return execute(args, "read_filtered tag=$tag", lines)
    }

    private fun readErrors(lines: Int): ConnectorOutput {
        val args = listOf("-d", "-t", lines.toString(), "*:E")
        return execute(args, "read_errors", lines)
    }

    private fun execute(args: List<String>, description: String, requestedLines: Int): ConnectorOutput {
        return runCatching {
            val output = runLogcat(args)
            Log.i("AIRI_PROOF", "LOGCAT_READ action=$description lines=${output.size}")
            ConnectorOutput.Success(
                text = output.joinToString("\n"),
                data = mapOf(
                    "line_count"       to output.size.toString(),
                    "requested_lines"  to requestedLines.toString(),
                ),
            )
        }.getOrElse { t ->
            Log.w("AIRI_PROOF", "LOGCAT_READ_FAILED action=$description cause=${t.message}")
            ConnectorOutput.Failure(
                code = "logcat_error",
                message = "${t.javaClass.simpleName}: ${t.message}",
                retryable = true,
            )
        }
    }

    private fun runLogcat(extraArgs: List<String>): List<String> {
        val cmd = mutableListOf("logcat") + extraArgs
        val process = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        val lines = mutableListOf<String>()
        BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
            var line: String?
            while (reader.readLine().also { line = it } != null && lines.size < MAX_LINES) {
                lines += line!!
            }
        }
        runCatching { process.destroy() }
        return lines
    }

    companion object {
        private const val DEFAULT_LINES = 100
        private const val MAX_LINES     = 500
    }
}
