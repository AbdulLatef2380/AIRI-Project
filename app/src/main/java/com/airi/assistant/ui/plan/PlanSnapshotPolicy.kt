package com.airi.assistant.ui.plan

/** Pure validation used before a snapshot is persisted or restored. */
object PlanSnapshotPolicy {
    fun canRestore(snapshot: PlanSnapshot): Boolean =
        snapshot.steps.isNotEmpty() && snapshot.goal.isNotBlank() &&
            snapshot.sessionId.isNotBlank() && snapshot.steps.all { it.id.isNotBlank() && it.label.isNotBlank() }

    fun containsSecretMaterial(snapshot: PlanSnapshot): Boolean {
        val values = listOf(snapshot.goal, snapshot.memoryQuery) + snapshot.skillIds + snapshot.connectorIds +
            snapshot.steps.flatMap { listOf(it.id, it.label, it.detail.orEmpty(), it.subLabel.orEmpty()) }
        return values.any { value ->
            val lower = value.lowercase()
            lower.contains("api_key") || lower.contains("authorization:") || lower.contains("bearer ") ||
                lower.contains("password") || lower.contains("secret") || lower.contains("sha256/")
        }
    }
}
