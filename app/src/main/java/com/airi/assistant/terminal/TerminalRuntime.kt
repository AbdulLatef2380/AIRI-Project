package com.airi.assistant.terminal

import android.util.Log
import com.airi.assistant.agent.sandbox.SandboxLogEntry
import com.airi.assistant.agent.sandbox.SandboxManager
import com.airi.assistant.agent.sandbox.SandboxSession
import com.airi.assistant.security.PermissionGovernanceLayer
import com.airi.assistant.security.CommandRedactor
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.AgentActivityBus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import java.util.LinkedList
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

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
    private val governance: PermissionGovernanceLayer,
    private val executionGateway: TerminalExecutionGateway =
        TerminalExecutionGateway(sandboxManager, governance),
    /** Optional context for history persistence; disabled by default for privacy. */
    private val context: android.content.Context? = null,
    private val historyPersistenceEnabled: Boolean = false
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
    @Volatile private var activeExecutionJob: Job? = null
    private val commandInFlight = AtomicBoolean(false)

    private val scrollback = LinkedList<TerminalLine>()
    private val historyBuffer = ArrayDeque<String>()
    private var historyIndex   = -1

    private val _commandHistoryFlow = MutableStateFlow<List<String>>(emptyList())
    /** Publicly observable command history (most-recent first). */
    val commandHistory: StateFlow<List<String>> = _commandHistoryFlow.asStateFlow()

    // T33: Persist history across process restarts via SharedPreferences.
    private val historyPrefs by lazy {
        context?.takeIf { historyPersistenceEnabled }
            ?.getSharedPreferences("airi_terminal_history", android.content.Context.MODE_PRIVATE)
    }
    private val PREF_HISTORY = "cmd_history"
    private val MAX_PERSISTED_HISTORY = 50

    init {
        restoreHistory()
    }

    private fun restoreHistory() {
        val stored = historyPrefs?.getString(PREF_HISTORY, null) ?: return
        val historyEntries = stored.split("\n").filter { it.isNotBlank() }
        historyEntries.reversed().forEach { historyBuffer.addFirst(it) }
        Log.d(TAG, "Restored ${historyBuffer.size} history entries")
    }

    private fun persistHistory() {
        historyPrefs?.edit()
            ?.putString(PREF_HISTORY, historyBuffer.take(MAX_PERSISTED_HISTORY).joinToString("\n"))
            ?.apply()
    }

    // ── Session management ────────────────────────────────────────────────────
    private var activeSession: TerminalSession? = null

    fun ensureSession(label: String = "Terminal") {
        if (activeSession != null) return
        val sandbox = executionGateway.createSession("terminal:$label") ?: return
        activeSession = TerminalSession(
            sessionId = UUID.randomUUID().toString().take(8),
            label     = label,
            sandboxId = sandbox.sessionId
        )
        Log.i(TAG, "Terminal session started: ${activeSession?.sessionId}")
    }

    // ── Command execution ─────────────────────────────────────────────────────

    suspend fun execute(rawCommand: String, agentId: String = "terminal") {
        val command = rawCommand.trim()
        if (command.isBlank()) return
        if (!commandInFlight.compareAndSet(false, true)) {
            appendLine(TerminalLine(text = "Permission denied: another terminal command is already running", isError = true))
            return
        }
        val executionJob = currentCoroutineContext()[Job]
        activeExecutionJob = executionJob

        // Persist and display only a redacted form; execute the original in the gateway.
        val safeCommand = CommandRedactor.redact(command)
        appendLine(TerminalLine(text = "$ $safeCommand", isInput = true))
        historyBuffer.addFirst(safeCommand)
        _commandHistoryFlow.value = historyBuffer.toList()
        historyIndex = -1
        persistHistory()

        // Built-in commands
        when (command.lowercase()) {
            "clear"  -> { _lines.value = emptyList(); commandInFlight.set(false); return }
            "help"   -> { appendHelp(); commandInFlight.set(false); return }
            "exit"   -> { activeSession?.let { executionGateway.closeSession(it.sandboxId) }; activeSession = null; commandInFlight.set(false); return }
        }

        // Governance check
        _isRunning.value = true
        AgentActivityBus.emit("Terminal: $safeCommand", ActivityCategory.SANDBOX)

        val sandboxSessionId = activeSession?.sandboxId
        val sandboxSession   = sandboxSessionId?.let { executionGateway.getSession(it) }
            ?: run {
                ensureSession()
                activeSession?.sandboxId?.let { executionGateway.getSession(it) }
            }

        if (sandboxSession == null) {
            appendLine(TerminalLine(text = "Error: No sandbox session available", isError = true))
            _isRunning.value = false
            if (activeExecutionJob === executionJob) activeExecutionJob = null
            commandInFlight.set(false)
            return
        }

        try {
            val gatewayResult = withContext(Dispatchers.IO) {
                executionGateway.execute(sandboxSession, command, agentId)
            }

            when (gatewayResult) {
                is TerminalExecutionGateway.Result.Disabled ->
                    appendLine(TerminalLine(text = gatewayResult.reason, isError = true))
                is TerminalExecutionGateway.Result.Denied ->
                    appendLine(TerminalLine(text = "Permission denied: ${gatewayResult.reason}", isError = true))
                is TerminalExecutionGateway.Result.Completed -> when (val result = gatewayResult.result) {
                is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.Success -> {
                    val output = stripAnsi(result.output)
                    if (output.isNotBlank()) {
                        output.lines().forEach { appendLine(TerminalLine(text = it)) }
                    }
                }
                is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.Failure -> {
                    appendLine(TerminalLine(text = result.error, isError = true))
                }
                com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.Timeout -> {
                    appendLine(TerminalLine(text = "Timeout: command exceeded time limit", isError = true))
                }
                com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.UnsupportedOnDevice -> {
                    appendLine(TerminalLine(text = "Command not available on this device", isError = true))
                }
                is com.airi.assistant.agent.sandbox.SandboxExecutor.ExecutionResult.SecurityViolation -> {
                    appendLine(TerminalLine(text = "Security violation: ${result.reason}", isError = true))
                }
            }
            }
        } catch (_: CancellationException) {
            appendLine(TerminalLine(text = "Command cancelled", isError = true))
            throw CancellationException("Terminal command cancelled")
        } catch (e: Exception) {
            appendLine(TerminalLine(text = "Error: ${e.message}", isError = true))
        } finally {
            if (activeExecutionJob === executionJob) activeExecutionJob = null
            _isRunning.value = false
            commandInFlight.set(false)
        }
    }

    /** Cancels the active command; SandboxExecutor destroys its process in finally. */
    fun cancelActiveCommand(): Boolean {
        val job = activeExecutionJob ?: return false
        job.cancel(CancellationException("Cancelled from terminal controls"))
        return true
    }

    // ── History navigation ────────────────────────────────────────────────────

    fun historyUp(): String? {
        if (historyBuffer.isEmpty()) return null
        historyIndex = (historyIndex + 1).coerceAtMost(historyBuffer.size - 1)
        return historyBuffer[historyIndex]
    }

    fun historyDown(): String? {
        if (historyIndex <= 0) { historyIndex = -1; return "" }
        historyIndex--
        return historyBuffer[historyIndex]
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
