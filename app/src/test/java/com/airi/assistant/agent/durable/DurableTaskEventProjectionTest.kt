package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DurableTaskEventProjectionTest {
    @Test
    fun rebuildsOrderedTimelineFromJournalRows() {
        val rows = listOf(
            row(sequence = 1, eventId = "e2", key = "k2", type = TaskTimelineEventType.RUN_STARTED),
            row(sequence = 0, eventId = "e1", key = "k1", type = TaskTimelineEventType.TASK_REGISTERED),
        )

        val projection = DurableTaskEventProjection.rebuild(rows)

        assertEquals(
            listOf(TaskTimelineEventType.TASK_REGISTERED, TaskTimelineEventType.RUN_STARTED),
            projection.map { it.type },
        )
    }

    @Test
    fun rejectsSequenceGapsAndDuplicateIdempotencyKeys() {
        assertThrows(IllegalArgumentException::class.java) {
            DurableTaskEventProjection.rebuild(
                listOf(row(sequence = 1, eventId = "e2", key = "k2", type = TaskTimelineEventType.RUN_STARTED))
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            DurableTaskEventProjection.rebuild(
                listOf(
                    row(sequence = 0, eventId = "e1", key = "same", type = TaskTimelineEventType.TASK_REGISTERED),
                    row(sequence = 1, eventId = "e2", key = "same", type = TaskTimelineEventType.RUN_STARTED),
                )
            )
        }
    }

    private fun row(
        sequence: Long,
        eventId: String,
        key: String,
        type: TaskTimelineEventType,
    ) = DurableTaskEventEntity(
        taskId = "task-1",
        eventId = eventId,
        sequence = sequence,
        type = type.name,
        summary = type.name,
        detail = "",
        runId = null,
        stepId = null,
        recordedAtMs = sequence,
        idempotencyKey = key,
    )
}
