package com.airi.assistant.ui.viewmodel

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.airi.assistant.ai.agent.trace.AgentTrace
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.skill.SkillService
import com.airi.assistant.data.run.AgentRunStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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

    /**
     * Runs the in-memory store no longer holds but Room still does (Part 5 §6), rebuilt read-only and
     * cached by id so a row is read back once.
     */
    private val roomOnly = MutableStateFlow<Map<String, AgentTrace>>(emptyMap())

    /**
     * The one list the run list, the deep link and the detail selection read: live and recent runs from
     * the trace store, plus older runs only Room kept. Never longer than the person's retention level.
     */
    val traces: StateFlow<List<AgentTrace>> = combine(traceManager.traces, roomOnly) { memory, restored ->
        mergeRunSources(memory, restored.values, traceManager.maxTraces)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, traceManager.traces.value)

    private val _debugMode = MutableStateFlow(
        preferences.getBoolean("agent_debug_mode", false)
    )
    val debugMode: StateFlow<Boolean> = _debugMode.asStateFlow()

    private val _selectedTrace = MutableStateFlow<AgentTrace?>(null)
    val selectedTrace: StateFlow<AgentTrace?> = _selectedTrace.asStateFlow()

    init {
        viewModelScope.launch {
            AgentRunStore.observeRunsOrEmpty().collect { rows ->
                val known = roomOnly.value
                val wanted = rows.map { it.id }.toSet()
                val added = rows.filter { it.id !in known }.mapNotNull { row ->
                    val trace = try {
                        withContext(Dispatchers.IO) { AgentRunStore.loadTrace(row.id) }
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                    trace?.let { row.id to it }
                }.toMap()
                roomOnly.value = known.filterKeys { it in wanted } + added
            }
        }
        // Keep the open detail screen live: while a run is in progress the trace with the same
        // id is replaced on every step, and again by the final record when the run ends.
        viewModelScope.launch {
            traces.collect { list ->
                val current = _selectedTrace.value ?: return@collect
                val fresh = list.firstOrNull { it.id == current.id } ?: return@collect
                if (fresh != current) _selectedTrace.value = fresh
            }
        }
    }

    private val _retention = MutableStateFlow(com.airi.assistant.core.TraceRetention.levelFrom(appContext))
    val retention: StateFlow<com.airi.assistant.core.TraceRetention.Level> = _retention.asStateFlow()

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

    fun setRetention(level: com.airi.assistant.core.TraceRetention.Level) {
        _retention.value = level
        com.airi.assistant.core.TraceRetention.save(appContext, level)
    }

    fun selectTrace(trace: AgentTrace?) {
        _selectedTrace.value = trace
    }

    fun clearLogs() {
        traceManager.clearTraces()
        // Runs that only Room holds are not in the trace store, so its removal hook never sees them.
        viewModelScope.launch { runCatching { AgentRunStore.clearIfReady() } }
    }

    fun deleteTrace(trace: AgentTrace) {
        if (_selectedTrace.value?.id == trace.id) _selectedTrace.value = null
        traceManager.deleteTrace(trace.id)
        viewModelScope.launch { runCatching { AgentRunStore.deleteIfReady(trace.id) } }
    }

    fun getSkillInfos() = skillService.getAllSkillInfos()

    fun setSkillEnabled(name: String, enabled: Boolean) =
        skillService.setSkillEnabled(name, enabled)

    fun getToolList(): List<Pair<String, String>> = skillService.getToolList()
}

/**
 * Merges the live trace list with runs only Room holds. Ids present in memory win (they are fuller);
 * the result is newest first and capped at [limit] so the person's retention level still bounds it.
 */
internal fun mergeRunSources(
    memory: List<AgentTrace>,
    fromRoom: Collection<AgentTrace>,
    limit: Int,
): List<AgentTrace> {
    if (fromRoom.isEmpty()) return memory
    val known = HashSet<String>(memory.size * 2).apply { memory.forEach { add(it.id); add(it.traceId) } }
    val extra = fromRoom.filter { it.id !in known && it.traceId !in known }
    if (extra.isEmpty()) return memory
    val live = memory.filter { it.inProgress }
    val settled = (memory.filterNot { it.inProgress } + extra).sortedByDescending { it.timestamp }
    return live + settled.take((limit - live.size).coerceAtLeast(0))
}
