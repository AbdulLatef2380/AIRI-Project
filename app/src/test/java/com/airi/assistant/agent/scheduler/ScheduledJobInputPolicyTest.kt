package com.airi.assistant.agent.scheduler

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScheduledJobInputPolicyTest {

    @Test
    fun acceptsBoundedJobInput() {
        val result = ScheduledJobInputPolicy.validate(
            agentId = "productivity",
            payload = "Summarize the project status",
            label = "Project status"
        )

        assertEquals(ScheduledJobInputPolicy.ValidationResult.Accepted, result)
    }

    @Test
    fun rejectsBlankAgentIdentifier() {
        assertRejected(
            ScheduledJobInputPolicy.validate(
                agentId = " ",
                payload = "payload",
                label = "label"
            )
        )
    }

    @Test
    fun rejectsBlankPayload() {
        assertRejected(
            ScheduledJobInputPolicy.validate(
                agentId = "productivity",
                payload = "",
                label = "label"
            )
        )
    }

    @Test
    fun rejectsUtf8PayloadAboveWorkManagerBudget() {
        val payload = "م".repeat(ScheduledJobInputPolicy.MAX_PAYLOAD_UTF8_BYTES)

        assertRejected(
            ScheduledJobInputPolicy.validate(
                agentId = "productivity",
                payload = payload,
                label = "label"
            )
        )
    }

    @Test
    fun rejectsOverlongLabel() {
        assertRejected(
            ScheduledJobInputPolicy.validate(
                agentId = "productivity",
                payload = "payload",
                label = "x".repeat(ScheduledJobInputPolicy.MAX_LABEL_CHARS + 1)
            )
        )
    }

    private fun assertRejected(result: ScheduledJobInputPolicy.ValidationResult) {
        assertTrue(result is ScheduledJobInputPolicy.ValidationResult.Rejected)
    }
}
