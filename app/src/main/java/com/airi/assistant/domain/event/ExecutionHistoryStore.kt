package com.airi.assistant.domain.event

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.onEach
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ExecutionHistoryStore(private val context: Context) {

    data class HistoryEntry(
        val eventType: String,
        val timestamp: Long,
        val details: String,
        val success: Boolean?,
        val runId: String = "",
        val formattedTime: String = SimpleDateFormat(
            "HH:mm:ss", Locale.getDefault()
        ).format(Date(System.currentTimeMillis()))
    )

    private val prefs = context.getSharedPreferences("airi_execution_history", Context.MODE_PRIVATE)
    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lock = Any()
    private val _entries = MutableStateFlow<List<HistoryEntry>>(loadEntries())
    /** Single persisted/history source for observability screens and exports. */
    val entries: StateFlow<List<HistoryEntry>> = _entries.asStateFlow()

    companion object {
        private const val MAX_ENTRIES = 200
        private const val KEY_HISTORY = "history"
    }

    init {
        // Subscribe to EventBus and persist every event automatically
        EventBus.events
            .onEach { event -> record(event) }
            .launchIn(scope)
    }

    fun record(event: AppEvent) {
        val entry = event.toHistoryEntry() ?: return
        synchronized(lock) {
            val current = _entries.value.toMutableList()
            current.add(entry)
            while (current.size > MAX_ENTRIES) current.removeAt(0)
            val snapshot = current.toList()
            prefs.edit().putString(KEY_HISTORY, gson.toJson(snapshot)).apply()
            _entries.value = snapshot
        }
    }

    fun getEntries(): List<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<HistoryEntry>>(
                json, object : TypeToken<List<HistoryEntry>>() {}.type
            )
        }.getOrElse { emptyList() }
    }

    fun getRecentEntries(count: Int = 50): List<HistoryEntry> =
        getEntries().takeLast(count).reversed()

    fun getEntriesByType(type: String): List<HistoryEntry> =
        getEntries().filter { it.eventType == type }

    fun clear() {
        synchronized(lock) {
            prefs.edit().remove(KEY_HISTORY).apply()
            _entries.value = emptyList()
        }
    }

    private fun loadEntries(): List<HistoryEntry> {
        val json = prefs.getString(KEY_HISTORY, null) ?: return emptyList()
        return runCatching {
            gson.fromJson<List<HistoryEntry>>(
                json, object : TypeToken<List<HistoryEntry>>() {}.type
            )
        }.getOrElse { emptyList() }.takeLast(MAX_ENTRIES)
    }

    private fun AppEvent.toHistoryEntry(): HistoryEntry? = when (this) {
        is AppEvent.AgentExecutionStarted ->
            HistoryEntry("AgentStarted", timestamp, "Execution started", null, traceId.take(40))
        is AppEvent.AgentExecutionSuccess ->
            HistoryEntry("AgentSuccess", timestamp, "Execution completed (${durationMs.coerceAtLeast(0)}ms)", true, traceId.take(40))
        is AppEvent.AgentExecutionFailed ->
            HistoryEntry("AgentFailed",  timestamp, "Execution failed: ${safeTag(error)}", false, traceId.take(40))
        is AppEvent.AgentExecutionTimeout ->
            HistoryEntry("AgentTimeout", timestamp, "Execution timed out", false, traceId.take(40))
        is AppEvent.AgentExecutionCancelled ->
            HistoryEntry("AgentCancelled", timestamp, "Execution cancelled: ${safeTag(reason)}", null, traceId.take(40))
        is AppEvent.PolicyChecked ->
            HistoryEntry("Policy", timestamp, "$rule: ${if (passed) "" else ""}${reason?.let { " — $it" } ?: ""}", passed)
        is AppEvent.SkillExecutionStarted ->
            HistoryEntry("SkillStarted", timestamp, skillName, null)
        is AppEvent.SkillExecutionCompleted ->
            HistoryEntry("Skill", timestamp, "$skillName (${durationMs}ms)", success)
        is AppEvent.ToolCallExecuted ->
            HistoryEntry("Tool", timestamp, toolName, success)
        is AppEvent.UserSignedIn ->
            HistoryEntry("SignIn", timestamp, "Method: $method", true)
        is AppEvent.UserSignedOut ->
            HistoryEntry("SignOut", timestamp, "", null)
        is AppEvent.AuthFailed ->
            HistoryEntry("AuthFail", timestamp, safeTag(reason), false)
        is AppEvent.SubscriptionChecked ->
            HistoryEntry("Sub", timestamp, "$feature: ${if (featureAllowed) "OK" else "BLOCKED"} [$tier]", featureAllowed)
        is AppEvent.UsageLimitReached ->
            HistoryEntry("Limit", timestamp, "$limitType: $current/$max", false)
        is AppEvent.PremiumRequired ->
            HistoryEntry("Premium", timestamp, feature, false)
        is AppEvent.PermissionGranted ->
            HistoryEntry("Permission", timestamp, "Granted: $permission", true)
        is AppEvent.PermissionDenied ->
            HistoryEntry("Permission", timestamp, "Denied: $permission (permanent=$permanent)", false)
        else -> null
    }

    /** Keep local history useful without persisting prompts, stack traces, or tokens. */
    private fun safeTag(raw: String): String = raw
        .replace(Regex("https?://\\S+", RegexOption.IGNORE_CASE), "[url]")
        .replace(Regex("[\\r\\n\\t]+"), " ")
        .replace(Regex("[^a-zA-Z0-9_ .:/-]"), "_")
        .trim()
        .take(120)
        .ifBlank { "unknown" }
}
