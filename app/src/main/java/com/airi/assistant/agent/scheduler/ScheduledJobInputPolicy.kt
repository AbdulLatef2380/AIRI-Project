package com.airi.assistant.agent.scheduler

/**
 * Bounds scheduled-job input before it is persisted or passed to WorkManager.
 *
 * These limits leave room below WorkManager's Data size limit and keep labels
 * suitable for the task-management UI. The policy is platform-independent so
 * it can be exercised by local unit tests.
 */
object ScheduledJobInputPolicy {
    const val MAX_AGENT_ID_CHARS = 128
    const val MAX_LABEL_CHARS = 256
    const val MAX_PAYLOAD_UTF8_BYTES = 6 * 1024
    const val SYSTEM_AGENT_ID = "system"

    private val allowedSystemMaintenancePayloads = setOf(
        "sandbox_reaper",
        "audit_log_pruner",
        "context_cache_pruner"
    )

    sealed interface ValidationResult {
        data object Accepted : ValidationResult
        data class Rejected(val message: String) : ValidationResult
    }

    fun validate(agentId: String, payload: String, label: String): ValidationResult = when {
        agentId.isBlank() -> ValidationResult.Rejected("A scheduled job requires an agent identifier.")
        agentId.length > MAX_AGENT_ID_CHARS -> ValidationResult.Rejected("The scheduled-job agent identifier is too long.")
        label.isBlank() -> ValidationResult.Rejected("A scheduled job requires a label.")
        label.length > MAX_LABEL_CHARS -> ValidationResult.Rejected("The scheduled-job label is too long.")
        payload.isBlank() -> ValidationResult.Rejected("A scheduled job requires a payload.")
        payload.toByteArray(Charsets.UTF_8).size > MAX_PAYLOAD_UTF8_BYTES ->
            ValidationResult.Rejected("The scheduled-job payload is too large.")
        agentId == SYSTEM_AGENT_ID && payload !in allowedSystemMaintenancePayloads ->
            ValidationResult.Rejected("Unknown system maintenance payload.")
        else -> ValidationResult.Accepted
    }

    fun isAllowedSystemMaintenancePayload(payload: String): Boolean =
        payload in allowedSystemMaintenancePayloads

    fun requireValid(agentId: String, payload: String, label: String) {
        val result = validate(agentId, payload, label)
        require(result is ValidationResult.Accepted) {
            (result as ValidationResult.Rejected).message
        }
    }
}
