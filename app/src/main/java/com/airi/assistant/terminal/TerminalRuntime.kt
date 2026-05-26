package com.airi.assistant.terminal

import android.util.Log
import com.airi.assistant.agent.sandbox.SandboxExecutor
import com.airi.assistant.agent.sandbox.SandboxLogEntry
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.agent.sandbox.SandboxSession
import com.airi.assistant.security.PermissionGovernanceLayer
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.UUID

/**
 * TerminalRuntime — persistent interactive shell runtime backed by [SandboxManager].
 *
 * Provides:
 *  - Session management (multiple terminal sessions, each with its own sandbox)
 *  - Command history with up-arrow navigation
 *  - Scrollback buffer (capped at [MAX_HISTORY_LINES])
 *  - ANSI escape code stripping for plain-text rendering
 *  - Governance check on every command via [PermissionGovernanceLayer]
 *  - Observable output for [TerminalComposable]
 */
class TerminalRuntime(
    private val sandboxManager: SandboxManager,
    private val governance:     PermissionGovernanceLayer
) {
    private val TAG = "TerminalRuntime"

    data class TerminalLine(
        val id:          String = UUID.randomUUID().toString().take(8),
        val text:        String,
        val isInput:     Boolean = false,
        val isError:     Boolean = false,
        val timestampMs: Long    = System.currentTimeMillis()
    )

    data class TerminalSession(
        val sessionId:   String,
        val label:       String,
        val sandboxId:   String,
        val createdAtMs: Long = System.currentTimeMillis()
    )

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // ── Output state ──────────────────────────────────────────────────────────
    private val _lines = MutableStateFlow<List<TerminalLine>>(listOf(
        TerminalLine(text = "AIRI Terminal — sandbox-restricted shell", isInput = false),
        TerminalLine(text = "Type 'help' for available commands.", isInput = false),
        TerminalLine(text = "", isInput = false)
    ))
    val lines: StateFlow<List<TerminalLine>> = _lines.asStateFlow()

    private val _isRunning = MutableStateFlow(false)
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val scrollback = LinkedList<TerminalLine>()
    private val commandHistory = ArrayDeque<String>()
    private var historyIndex   = -1

    // ── Session management ────────────────────────────────────────────────────
    private var activeSession: TerminalSession? = null

    fun ensureSession(label: String = "Terminal") {
        if (activeSession != null) return
        val sandbox = sandboxManager.createSession("terminal:$label") ?: return
        activeSession = TerminalSession(
            sessionId = UUID.randomUUID().toString().take(8),
            label     = label,
            sandboxId = sandbox.sessionId
        )
        Log.i(TAG, "Terminal session started: ${activeSession?.sessionId}")
    }

    // ── Command execution ─────────────────────────────────────────────────────

    suspend fun execute(rawCommand: String) {
        val command = rawCommand.trim()
        if (command.isBlank()) return

        // Record input line
        appendLine(TerminalLine(text = "$ $command", isInput = true))
        commandHistory.addFirst(command)
        historyIndex = -1

        // Built-in commands
        when (command.lowercase()) {
            "clear"  -> { _lines.value = emptyList(); return }
            "help"   -> { appendHelp(); return }
            "exit"   -> { activeSession?.let { sandboxManager.closeSession(it.sandboxId) }; activeSession = null; return }
        }

        // Governance check
        val decision = governance.evaluate("shell_command", command, "terminal", command)
        if (!decision.allowed) {
            appendLine(TerminalLine(text = "Permission denied: ${decision.reason}", isError = true))
            return
        }

        _isRunning.value = true
        AgentActivityBus.emit("Terminal: $command", ActivityCategory.SANDBOX)

        val sandboxSessionId = activeSession?.sandboxId
        val sandboxSession   = sandboxSessionId?.let { sandboxManager.getSession(it) }
            ?: run {
                ensureSession()
                activeSession?.sandboxId?.let { sandboxManager.getSession(it) }
            }

        if (sandboxSession == null) {
            appendLine(TerminalLine(text = "Error: No sandbox session available", isError = true))
            _isRunning.value = false
            return
        }

        try {
            val result = withContext(Dispatchers.IO) {
                SandboxExecutor(sandboxSession).execute(
                    SandboxExecutor.SandboxTask(
                        type    = SandboxExecutor.TaskType.SHELL_COMMAND,
                        command = command
                    )
                )
            }

            when (result) {
                is SandboxExecutor.ExecutionResult.Success -> {
                    val output = stripAnsi(result.output)
                    if (output.isNotBlank()) {
                        output.lines().forEach { appendLine(TerminalLine(text = it)) }
                    }
                }
                is SandboxExecutor.ExecutionResult.Failure -> {
                    appendLine(TerminalLine(text = result.error, isError = true))
                }
                SandboxExecutor.ExecutionResult.Timeout -> {
                    appendLine(TerminalLine(text = "Timeout: command exceeded time limit", isError = true))
                }
                SandboxExecutor.ExecutionResult.UnsupportedOnDevice -> {
                    appendLine(TerminalLine(text = "Command not available on this device", isError = true))
                }
                is SandboxExecutor.ExecutionResult.SecurityViolation -> {
                    appendLine(TerminalLine(text = "Security violation: ${result.reason}", isError = true))
                }
            }
        } catch (e: Exception) {
            appendLine(TerminalLine(text = "Error: ${e.message}", isError = true))
        } finally {
            _isRunning.value = false
        }
    }

    // ── History navigation ────────────────────────────────────────────────────

    fun historyUp(): String? {
        if (commandHistory.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(commandHistory.size - 1)
        return commandHistory[historyIndex]
    }

    fun historyDown(): String? {
        if (historyIndex <= 0) { historyIndex = -1; return "" }
        historyIndex--
        return commandHistory[historyIndex]
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun appendLine(line: TerminalLine) {
        scrollback.add(line)
        if (scrollback.size > MAX_HISTORY_LINES) scrollback.poll()
        _lines.value = scrollback.toList()
    }

    private fun appendHelp() {
        val help = listOf(
            "Available commands:",
            "  ls, cat, echo, mkdir, rm, cp, mv       — file operations",
            "  find, grep, head, tail, wc, sort, uniq — search & text",
            "  sed, awk                                — text processing",
            "  git status, git log, git diff           — git read-only",
            "  zip, unzip, tar                         — archive tools",
            "  clear                                   — clear terminal",
            "  exit                                    — close session",
            "",
            "Note: curl, wget, git-clone and network commands are not",
            "available in the sandbox for security reasons."
        )
        help.forEach { appendLine(TerminalLine(text = it)) }
    }

    private fun stripAnsi(input: String): String =
        input.replace(Regex("\u001B\\[[0-9;]*[mGKHF]"), "")

    fun clearOutput() {
        scrollback.clear()
        _lines.value = emptyList()
    }

    companion object {
        private const val MAX_HISTORY_LINES = 500
    }
}
