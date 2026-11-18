package com.airi.assistant.ui.generative

/** Bounded process-local telemetry for Gen UI provider compliance. */
object GenerativeUiComplianceMetrics {
    data class Snapshot(
        val provider: String,
        val accepted: Long,
        val rejected: Long,
        val rejectionReasons: Map<String, Long>,
    ) {
        val attempts: Long get() = accepted + rejected
        val acceptanceRate: Double get() = if (attempts == 0L) 0.0 else accepted.toDouble() / attempts
    }

    private data class Mutable(var accepted: Long = 0, var rejected: Long = 0, val reasons: MutableMap<String, Long> = linkedMapOf())
    private val values = linkedMapOf<String, Mutable>()

    @Synchronized
    fun record(providerId: String, result: GenerativeUiCompileResult) {
        val provider = providerId.trim().ifBlank { "unknown" }.take(64)
        val counter = values.getOrPut(provider) { Mutable() }
        when (result) {
            is GenerativeUiCompileResult.Accepted -> counter.accepted++
            is GenerativeUiCompileResult.Rejected -> {
                counter.rejected++
                val reason = result.reason.name.lowercase()
                counter.reasons[reason] = (counter.reasons[reason] ?: 0L) + 1L
            }
        }
    }

    @Synchronized
    fun snapshot(providerId: String): Snapshot {
        val value = values[providerId.trim().ifBlank { "unknown" }]
        return Snapshot(providerId, value?.accepted ?: 0L, value?.rejected ?: 0L, value?.reasons?.toMap().orEmpty())
    }

    @Synchronized
    fun resetForTesting() = values.clear()
}
