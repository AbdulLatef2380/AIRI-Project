package com.airi.assistant.ui.activity

import java.util.UUID

data class ActivityEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String,
    val detail: String? = null,
    val category: ActivityCategory,
    val severity: ActivitySeverity = ActivitySeverity.INFO
)

enum class ActivityCategory(val label: String, val emoji: String) {
    REASONING    ("Reasoning",     "🧠"),
    TOOL         ("Tool",          "🔧"),
    CONNECTOR    ("Connector",     "🔌"),
    VOICE        ("Voice",         "🎙"),
    ROUTING      ("Routing",       "🔀"),
    MEMORY       ("Memory",        "💾"),
    SANDBOX      ("Sandbox",       "📦"),
    ORCHESTRATION("Orchestration", "⚙"),
    MODEL        ("Model",         "🤖"),
    ACCESSIBILITY("Accessibility", "♿"),
    SYSTEM       ("System",        "📡"),
    CONTEXT_RESET("Context Reset", "⚠️")
}

enum class ActivitySeverity { INFO, WARN, ERROR }
