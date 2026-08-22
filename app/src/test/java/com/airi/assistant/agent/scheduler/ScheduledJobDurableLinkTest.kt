package com.airi.assistant.agent.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScheduledJobDurableLinkTest {

    @Test
    fun scheduledJobRetainsExecutionScopeAndLatestDurableTaskReference() {
        val pending = ScheduledJob(
            id = "job-1",
            agentId = "research",
            payload = "Summarize the approved project sources",
            label = "Weekly research summary",
            type = ScheduleType.PERIODIC,
            triggerAtMs = 1_000L,
            intervalMs = 15 * 60_000L,
            projectId = "project-1",
            ownerId = "user-1",
            privacyLevel = 0
        )

        val completed = pending.copy(
            lastRunAtMs = 2_000L,
            lastOutcome = ScheduledJobOutcome.COMPLETED,
            lastDurableTaskId = "task-1"
        )

        assertEquals("project-1", completed.projectId)
        assertEquals("user-1", completed.ownerId)
        assertEquals(0, completed.privacyLevel)
        assertEquals("task-1", completed.lastDurableTaskId)
        assertEquals(ScheduledJobOutcome.COMPLETED, completed.lastOutcome)
    }

    @Test
    fun maintenanceJobHasNoDurableTaskReferenceByDefault() {
        val maintenance = ScheduledJob(
            id = "job-2",
            agentId = "system",
            payload = "audit_log_pruner",
            label = "Audit maintenance",
            type = ScheduleType.ONE_TIME,
            triggerAtMs = 1_000L,
            intervalMs = null
        )

        assertNull(maintenance.lastDurableTaskId)
        assertEquals("scheduled", maintenance.ownerId)
    }
}
