package com.airi.assistant.ui.plan

data class PlanStepModel(
    val id: String,
    val label: String,
    val subLabel: String? = null,
    val status: PlanStepStatus = PlanStepStatus.QUEUED,
    val retryCount: Int = 0,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    val detail: String? = null,
    val children: List<PlanStepModel> = emptyList()
) {
    val elapsedLabel: String?
        get() {
            val start = startedAtMs ?: return null
            val end = finishedAtMs ?: System.currentTimeMillis()
            val ms = end - start
            return when {
                ms < 1_000  -> "${ms}ms"
                ms < 60_000 -> "${ms / 1_000}s"
                else        -> "${ms / 60_000}m ${(ms % 60_000) / 1_000}s"
            }
        }
}

enum class PlanStepStatus {
    QUEUED, RUNNING, COMPLETED, FAILED, RETRYING, CANCELLED;
    val isTerminal get() = this == COMPLETED || this == FAILED || this == CANCELLED
    val isActive   get() = this == RUNNING || this == RETRYING
}
