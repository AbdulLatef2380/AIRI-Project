package com.airi.assistant.ui.activity

/**
 * One observable step in an agent run.
 *
 * Keep this model immutable: when the producer publishes a new task list with an updated
 * status, Compose receives the new value and recomposes the rows that display it.
 * `detailLog` must be redacted by the event producer before it reaches this UI; never pass
 * credentials, private file contents, or hidden internal reasoning as a detail log.
 */
data class AgentTask(
    val id: String,
    val title: String,
    val status: TaskStatus,
    val detailLog: String? = null,
)

/** Lifecycle states displayed by [AgentLifecycleTrackerPanel]. */
enum class TaskStatus {
    PENDING,
    RUNNING,
    COMPLETED,
    FAILED,
}
