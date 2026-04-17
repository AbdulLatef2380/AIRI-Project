package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.AgentTraceManager
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

    // ── UI State ──────────────────────────────────────────────────────────────

    val traces: StateFlow<List<AgentTrace>> = traceManager.traces

    private val _debugMode = MutableStateFlow(
        preferences.getBoolean("agent_debug_mode", false)
    )
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _selectedTrace = MutableStateFlow<AgentTrace?>(null)
    val selectedTrace: StateFlow<AgentTrace?> = _selectedTrace.asStateFlow()

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

    fun getSkillInfos() = skillService.getAllSkillInfos()

    fun setSkillEnabled(name: String, enabled: Boolean) =
        skillService.setSkillEnabled(name, enabled)

    fun getToolList(): List<Pair<String, String>> = listOf(
        "github_get_user"        to "GitHub",
        "github_get_repos"       to "GitHub",
        "telegram_send_message"  to "Telegram",
        "gmail_list_emails"      to "Gmail",
        "drive_search_file"      to "Google Drive",
        "calendar_next_events"   to "Google Calendar"
    )
}
