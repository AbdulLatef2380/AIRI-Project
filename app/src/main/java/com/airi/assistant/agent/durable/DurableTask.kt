package com.airi.assistant.agent.durable

import java.util.UUID

/**
 * A task that must survive app closure and be resumable across process restarts.
 *
 * Durable tasks differ from in-memory tasks in that their state is persisted
 * to disk (JSON file in the app's files directory) and executed via
 * WorkManager so Android can schedule them even when the app is backgrounded.
 *
 * ─────────────────────────────────────────────────────────────────────────
 * LIFECYCLE
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   QUEUED → RUNNING → COMPLETED
 *                    → FAILED      (if agent returns AgentEvent.Failed)
 *                    → CANCELLED   (user-initiated or policy kill-switch)
 *   QUEUED → RUNNING → PAUSED      (app backgrounded, task checkpointed)
 *   PAUSED → RUNNING               (WorkManager resumes, checkpoint loaded)
 *
 * ─────────────────────────────────────────────────────────────────────────
 * CHECKPOINT SEMANTICS
 * ─────────────────────────────────────────────────────────────────────────
 *
 *   [checkpointData] is an opaque JSON string written by the sub-agent at
 *   regular intervals (e.g. after each completed step). On resume, the
 *   sub-agent reads this to skip already-completed work.
 *   For agents that do not support checkpointing, this remains empty.
 */
data class DurableTask(

    // ── Identity ─────────────────────────────────────────────────────────────

    /** Stable UUID. Used as WorkManager work name for deduplication. */
    val id: String = UUID.randomUUID().toString(),

    /** Human-readable title shown in notifications and task history UI. */
    val title: String,

    /** Detailed description of what the task does. */
    val description: String,

    // ── Routing ──────────────────────────────────────────────────────────────

    /** ID of the [SubAgent] that will execute this task. */
    val agentId: String,

    /** Full user input / task specification passed to the agent. */
    val input: String,

    // ── Scheduling ───────────────────────────────────────────────────────────

    /** Epoch ms when the task was queued. */
    val queuedAtMs: Long = System.currentTimeMillis(),

    /** Epoch ms when the task started running (-1 = not yet started). */
    val startedAtMs: Long = -1L,

    /** Epoch ms when the task completed/failed/cancelled (-1 = ongoing). */
    val finishedAtMs: Long = -1L,

    /** Whether the device must be connected to a network to execute. */
    val requiresNetwork: Boolean = false,

    /** Whether the device should be charging (for long-running tasks). */
    val requiresCharging: Boolean = false,

    // ── State ────────────────────────────────────────────────────────────────

    val status: DurableTaskStatus = DurableTaskStatus.QUEUED,

    /** Number of retry attempts so far. */
    val attemptCount: Int = 0,

    /** Maximum number of retry attempts before marking FAILED. */
    val maxAttempts: Int = 3,

    // ── Progress ─────────────────────────────────────────────────────────────

    /** 0–100 progress estimate. -1 = indeterminate. */
    val progressPercent: Int = -1,

    /** Human-readable status message for the notification. */
    val progressMessage: String = "",

    // ── Result ───────────────────────────────────────────────────────────────

    /** Final result text (set on COMPLETED). */
    val result: String = "",

    /** Error reason (set on FAILED). */
    val errorReason: String = "",

    // ── Resumability ─────────────────────────────────────────────────────────

    /**
     * Opaque JSON checkpoint written by the executing sub-agent.
     * Empty string means the task starts from scratch on resume.
     */
    val checkpointData: String = "",

    // ── Notification ─────────────────────────────────────────────────────────

    /** Whether to post a system notification for this task's progress. */
    val showNotification: Boolean = true
) {
    val isTerminal: Boolean
        get() = status == DurableTaskStatus.COMPLETED ||
                status == DurableTaskStatus.FAILED    ||
                status == DurableTaskStatus.CANCELLED

    val canRetry: Boolean
        get() = status == DurableTaskStatus.FAILED && attemptCount < maxAttempts
}

enum class DurableTaskStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    COMPLETED,
    FAILED,
    CANCELLED
}
