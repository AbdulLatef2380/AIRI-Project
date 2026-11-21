package com.airi.assistant.agent.durable

/**
 * Rebuilds the user-visible timeline from the durable event journal.
 * The journal is authoritative for ordering; duplicate idempotency keys and
 * sequence gaps are rejected instead of silently producing a misleading trace.
 */
object DurableTaskEventProjection {
    fun rebuild(rows: List<DurableTaskEventEntity>): List<TaskTimelineEvent> {
        val ordered = rows.sortedWith(compareBy<DurableTaskEventEntity> { it.sequence }.thenBy { it.eventId })
        val seenKeys = HashSet<String>(ordered.size)
        ordered.forEachIndexed { index, row ->
            require(seenKeys.add(row.idempotencyKey)) {
                "Duplicate durable event idempotency key for task=${row.taskId}: ${row.idempotencyKey}"
            }
            require(row.sequence == index.toLong()) {
                "Durable event sequence gap for task=${row.taskId}: expected=$index actual=${row.sequence}"
            }
        }
        return ordered.map { row ->
            TaskTimelineEvent(
                type = runCatching { TaskTimelineEventType.valueOf(row.type) }
                    .getOrElse { error("Unknown durable event type: ${row.type}") },
                summary = row.summary,
                detail = row.detail,
                runId = row.runId,
                stepId = row.stepId,
                recordedAtMs = row.recordedAtMs,
            )
        }
    }
}
