package com.airi.assistant.core

import android.content.Context
import com.airi.assistant.ai.agent.trace.AgentTraceManager
import com.airi.assistant.ui.activity.AgentActivityBus

/**
 * User-selectable retention for trace data. One setting drives every bounded
 * trace buffer so they stay consistent with each other.
 */
object TraceRetention {

    const val PREFS_NAME = "airi_ui_state"
    const val PREF_KEY = "trace_retention_level"

    enum class Level(val label: String, val traces: Int, val executions: Int, val events: Int, val messages: Int) {
        STANDARD("Standard", traces = 50, executions = 32, events = 400, messages = 200),
        EXTENDED("Extended", traces = 100, executions = 64, events = 800, messages = 400),
        MAXIMUM("Maximum", traces = 200, executions = 128, events = 1_500, messages = 800),
    }

    fun levelFrom(context: Context): Level {
        val name = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(PREF_KEY, Level.STANDARD.name)
        return Level.values().firstOrNull { it.name == name } ?: Level.STANDARD
    }

    fun save(context: Context, level: Level) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(PREF_KEY, level.name).apply()
        apply(level)
    }

    fun apply(level: Level) {
        AgentTraceManager.instance.maxTraces = level.traces
        ExecutionStatusBus.maxTraceHistory = level.executions
        AgentActivityBus.maxSnapshot = level.events
        ExecutionTraceArchive.maxMessages = level.messages
    }
}
