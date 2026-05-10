package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.core.ProofLogRepository
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.skill.SkillService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class AgentViewModel(application: Application) : AndroidViewModel(application) {

    private val appContext   = application.applicationContext
    private val preferences  = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
    private val traceManager = AgentTraceManager.instance

    // ── Domain services ───────────────────────────────────────────────────────
    private val skillService: SkillService = ServiceLocator.skillService

    // ── Trace state ───────────────────────────────────────────────────────────

    val traces: StateFlow<List<AgentTrace>> = traceManager.traces

    /** Alias kept for UI back-compat. */
    val agentTraces: StateFlow<List<AgentTrace>> get() = traces

    // ── Agent execution state (mirrored for AgentControlScreen) ───────────────

    private val _agentState = MutableStateFlow(AgentState())
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    private val _debugMode = MutableStateFlow(
        preferences.getBoolean("agent_debug_mode", false)
    )
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _selectedTrace = MutableStateFlow<AgentTrace?>(null)
    val selectedTrace: StateFlow<AgentTrace?> = _selectedTrace.asStateFlow()

    // ── Live AIRI_PROOF log state ─────────────────────────────────────────────

    /** Live list of parsed AIRI_PROOF logcat entries. Streams in real time. */
    val proofLog: StateFlow<List<ProofLogRepository.ProofLogEntry>> =
        ProofLogRepository.instance.entries

    /** True while the background logcat reader is active. */
    val isLogStreaming: StateFlow<Boolean> = ProofLogRepository.instance.isStreaming

    /** Non-null when the logcat stream encountered an error. */
    val logStreamError: StateFlow<String?> = ProofLogRepository.instance.errorMessage

    // ── Background agent ──────────────────────────────────────────────────────

    val backgroundAgentEnabled: Boolean
        get() = preferences.getBoolean("background_agent_enabled", false)

    val lastWorkerRunTime: Long
        get() = preferences.getLong("bg_agent_last_run", 0L)

    val lastWorkerRunTimeFormatted: String
        get() {
            val time = lastWorkerRunTime
            if (time == 0L) return "Never"
            return SimpleDateFormat("MMM d, HH:mm", Locale.getDefault()).format(Date(time))
        }

    val lastWorkerSummary: String
        get() = preferences.getString("bg_agent_last_result", "No data yet") ?: "No data yet"

    // ── Actions ───────────────────────────────────────────────────────────────

    fun setDebugMode(enabled: Boolean) {
        _debugMode.value = enabled
        preferences.edit().putBoolean("agent_debug_mode", enabled).apply()
        LoggingService.debug("AgentViewModel", "Debug mode set to $enabled")
    }

    fun selectTrace(trace: AgentTrace?) {
        _selectedTrace.value = trace
    }

    fun clearLogs() = traceManager.clearTraces()

    /** Alias kept for UI back-compat. */
    fun clearTraces() = clearLogs()

    /** Stop any running agent task and reset state. */
    fun stopAgent() { _agentState.value = AgentState() }

    // ── Live log actions ──────────────────────────────────────────────────────

    /** Start streaming AIRI_PROOF events. Call from DisposableEffect. */
    fun startLogStream() = ProofLogRepository.instance.start()

    /** Stop the logcat reader. Call from DisposableEffect's onDispose. */
    fun stopLogStream() = ProofLogRepository.instance.stop()

    /** Clear all accumulated AIRI_PROOF entries. */
    fun clearProofLog() = ProofLogRepository.instance.clear()

    // ── Skills ────────────────────────────────────────────────────────────────

    fun getSkillInfos() = skillService.getAllSkillInfos()

    fun setSkillEnabled(name: String, enabled: Boolean) =
        skillService.setSkillEnabled(name, enabled)

    fun getToolList(): List<Pair<String, String>> = skillService.getToolList()
}
