package com.airi.assistant.domain.background

/**
 * Stable identifiers and policy for all WorkManager-owned background work.
 * WorkManager input must carry only a reference (taskId/jobId), never the task
 * payload or credentials; the durable repository remains the source of truth.
 */
object BackgroundWorkNames {
    const val AGENT = "airi_background_agent"
    const val CLOUD_SYNC = "airi_cloud_sync"
    const val REENGAGEMENT = "airi_reengagement_reminder"
    const val MAINTENANCE_SANDBOX_REAPER = "system_sandbox_reaper"
    const val MAINTENANCE_AUDIT_PRUNER = "system_audit_log_pruner"
    const val MAINTENANCE_CONTEXT_CACHE_PRUNER = "system_context_cache_pruner"

    fun durableTask(taskId: String): String = "durable_task_$taskId"
}

enum class BackgroundTaskState {
    QUEUED, RUNNING, RETRYING, COMPLETED, FAILED, CANCELLED
}

data class BackgroundTaskRecord(
    val taskId: String,
    val state: BackgroundTaskState = BackgroundTaskState.QUEUED,
    val attempt: Int = 0,
    val maxAttempts: Int = 3,
    val checkpointVersion: Int = 1,
    val idempotencyKey: String = taskId,
    val updatedAtMs: Long = 0L
) {
    init {
        require(taskId.isNotBlank()) { "taskId must not be blank" }
        require(attempt >= 0) { "attempt must not be negative" }
        require(maxAttempts in 1..10) { "maxAttempts must be between 1 and 10" }
        require(attempt <= maxAttempts) { "attempt cannot exceed maxAttempts" }
        require(checkpointVersion >= 1) { "checkpointVersion must be positive" }
        require(idempotencyKey.isNotBlank()) { "idempotencyKey must not be blank" }
    }

    fun begin(nowMs: Long): BackgroundTaskRecord? {
        if (state == BackgroundTaskState.CANCELLED ||
            state == BackgroundTaskState.COMPLETED ||
            state == BackgroundTaskState.FAILED ||
            attempt >= maxAttempts
        ) return null
        return copy(state = BackgroundTaskState.RUNNING, attempt = attempt + 1, updatedAtMs = nowMs)
    }

    fun retry(nowMs: Long): BackgroundTaskRecord? =
        if (state == BackgroundTaskState.RUNNING && attempt < maxAttempts) {
            copy(state = BackgroundTaskState.RETRYING, updatedAtMs = nowMs)
        } else null

    fun terminal(state: BackgroundTaskState, nowMs: Long): BackgroundTaskRecord {
        require(state == BackgroundTaskState.COMPLETED ||
            state == BackgroundTaskState.FAILED ||
            state == BackgroundTaskState.CANCELLED)
        return copy(state = state, updatedAtMs = nowMs)
    }
}
