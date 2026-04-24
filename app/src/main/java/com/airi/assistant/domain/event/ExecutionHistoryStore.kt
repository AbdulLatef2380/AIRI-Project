package com.airi.assistant.domain.event

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
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
        val formattedTime: String = SimpleDateFormat(
            "HH:mm:ss", Locale.getDefault()
        ).format(Date(System.currentTimeMillis()))
    )

    private val prefs = context.getSharedPreferences("airi_execution_history", Context.MODE_PRIVATE)
    private val gson  = Gson()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

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
        val current = getEntries().toMutableList()
        current.add(entry)
        if (current.size > MAX_ENTRIES) current.removeAt(0)
        prefs.edit().putString(KEY_HISTORY, gson.toJson(current)).apply()
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
        prefs.edit().remove(KEY_HISTORY).apply()
    }

    private fun AppEvent.toHistoryEntry(): HistoryEntry? = when (this) {
        is AppEvent.AgentExecutionStarted ->
            HistoryEntry("AgentStarted", timestamp, "Input: ${input.take(80)}", null)
        is AppEvent.AgentExecutionSuccess ->
            HistoryEntry("AgentSuccess", timestamp, "Trace: $traceId (${durationMs}ms)", true)
        is AppEvent.AgentExecutionFailed ->
            HistoryEntry("AgentFailed",  timestamp, "Error: $error",              false)
        is AppEvent.AgentExecutionTimeout ->
            HistoryEntry("AgentTimeout", timestamp, "Trace: $traceId",            false)
        is AppEvent.AgentExecutionCancelled ->
            HistoryEntry("AgentCancelled", timestamp, reason,                     null)
        is AppEvent.PolicyChecked ->
            HistoryEntry("Policy", timestamp, "$rule: ${if (passed) "✓" else "✗"}${reason?.let { " — $it" } ?: ""}", passed)
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
            HistoryEntry("AuthFail", timestamp, reason, false)
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
}
