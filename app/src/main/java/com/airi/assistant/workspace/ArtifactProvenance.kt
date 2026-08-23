package com.airi.assistant.workspace

/**
 * Bounded execution metadata for a generated artifact.
 *
 * The artifact file remains private app storage. This record is intentionally
 * metadata-only: it links the result to its owning project and optional durable
 * task/run/step without copying prompts, tool arguments, secret values, raw
 * paths, provider responses, or model payloads into Room.
 */
data class ArtifactProvenance(
    val projectId: String,
    val taskId: String? = null,
    val runId: String? = null,
    val stepId: String? = null,
    val toolId: String? = null,
    val modelId: String? = null,
    val summary: String = ""
) {
    fun isWellFormed(): Boolean {
        if (projectId.isBlank() || projectId.length > MAX_ID_CHARS) return false
        if (!allBounded(taskId, runId, stepId, toolId, modelId)) return false
        if (summary.length > MAX_SUMMARY_CHARS || looksSensitive(summary)) return false
        val hasTask = !taskId.isNullOrBlank()
        if (hasTask != (!runId.isNullOrBlank() && !stepId.isNullOrBlank())) return false
        return true
    }

    private fun allBounded(vararg values: String?): Boolean = values.all { value ->
        value == null || (value.isNotBlank() && value.length <= MAX_ID_CHARS)
    }

    private fun looksSensitive(value: String): Boolean = SENSITIVE_VALUE.containsMatchIn(value)

    private companion object {
        const val MAX_ID_CHARS = 160
        const val MAX_SUMMARY_CHARS = 480
        val SENSITIVE_VALUE = Regex(
            "(?i)(api[_ -]?key|authorization|bearer\\s+[a-z0-9._-]{8,}|password|secret|token\\s*[:=])"
        )
    }
}
