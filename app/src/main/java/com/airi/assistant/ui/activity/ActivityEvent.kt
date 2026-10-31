package com.airi.assistant.ui.activity

import java.util.UUID

enum class ActivityPhase {
    ADMISSION,
    UNDERSTANDING,
    MODEL,
    ACTION,
    RESULT,
    LIFECYCLE,
    SYSTEM,
}

enum class ActivityVisibility {
    USER_VISIBLE,
    INTERNAL,
}

enum class ActivityStatus {
    PENDING,
    RUNNING,
    WAITING,
    COMPLETED,
    FAILED,
    CANCELLED,
    RECOVERING,
}

/** Typed presentation metadata; UI policy must not infer lifecycle from prose. */
data class ActivityMetadata(
    val phase: ActivityPhase = ActivityPhase.SYSTEM,
    val visibility: ActivityVisibility = ActivityVisibility.USER_VISIBLE,
    val status: ActivityStatus? = null,
    val parentId: String? = null,
    val actionId: String? = null,
)

data class ActivityEvent(
    val id: String = UUID.randomUUID().toString(),
    val timestampMs: Long = System.currentTimeMillis(),
    val message: String,
    val detail: String? = null,
    /** Owning agent execution when the event originates from a live trace. */
    val executionId: String? = null,
    val category: ActivityCategory,
    val severity: ActivitySeverity = ActivitySeverity.INFO,
    /** Stable machine-readable classification; unlike detail, never shown in the feed. */
    val machineTag: String? = null,
    /** Monotonic bus-assigned ordering for events sharing a millisecond timestamp. */
    val sequenceNumber: Long? = null,
    val metadata: ActivityMetadata = ActivityMetadata(),
) {
    companion object {
        const val MACHINE_TAG_NATIVE_CONTEXT_RESET = "native_context_reset"
    }
}

enum class ActivityCategory(val label: String, val emoji: String) {
    REASONING    ("Reasoning",     "◉"),
    TOOL         ("Tool",          ""),
    CONNECTOR    ("Connector",     ""),
    VOICE        ("Voice",         ""),
    ROUTING      ("Routing",       ""),
    MEMORY       ("Memory",        ""),
    SANDBOX      ("Sandbox",       ""),
    ORCHESTRATION("Orchestration", ""),
    MODEL        ("Model",         "◈"),
    ACCESSIBILITY("Accessibility", ""),
    SYSTEM       ("System",        ""),
    CONTEXT_RESET("Context Reset", "!")
}

enum class ActivitySeverity { INFO, WARN, ERROR }
