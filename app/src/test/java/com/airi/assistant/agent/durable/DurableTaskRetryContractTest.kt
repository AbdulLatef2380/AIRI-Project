package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskRetryContractTest {
    @Test
    fun retryReturnsQueuedAndKeepsTaskNonTerminal() {
        val task = DurableTask(
            id = "task-retry",
            title = "Retryable task",
            description = "fixture",
            agentId = "research",
            input = "fixture",
            plan = listOf(TaskPlanStep(id = "step", title = "Run"))
        )
        val running = task.beginRun("run-1", "step", 1_000L)
        val retrying = running.retry(2_000L)
        assertEquals(DurableTaskStatus.QUEUED, retrying.status)
        assertEquals(1, retrying.attemptCount)
        assertTrue(!retrying.isTerminal)
        assertEquals("Waiting for retry", retrying.progressMessage)
    }
}
