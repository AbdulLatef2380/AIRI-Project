package com.airi.assistant.telemetry

/**
 * Closed vocabulary for operational telemetry. Unknown values are collapsed to
 * `unknown` so a caller cannot turn a metric tag into a user/content channel.
 */
object TelemetryTagPolicy {
    private val areas = setOf(
        "cloud_failover", "session_load", "session_summary", "retrieval",
        "persistence", "connector", "readiness", "cancellation", "wipe"
    )
    private val states = setOf(
        "started", "attempted", "succeeded", "failed", "empty", "stale",
        "cancelled", "unavailable", "exhausted", "ready", "loading"
    )
    private val reasons = setOf(
        "transient_failure", "provider_switch", "all_providers_failed",
        "db_error", "completion_ignored", "valid_empty", "success",
        "save_error", "retrieval_error", "consent_denied", "unknown"
    )

    fun area(raw: String): String = raw.takeIf { it in areas } ?: "unknown"
    fun state(raw: String): String = raw.takeIf { it in states } ?: "unknown"
    fun reason(raw: String): String = raw.takeIf { it in reasons } ?: "unknown"

    fun dimension(raw: String, allowed: Set<String>): String =
        raw.takeIf { it in allowed } ?: "unknown"
}
