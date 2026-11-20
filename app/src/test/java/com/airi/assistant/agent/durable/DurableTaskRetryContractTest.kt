package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    @Test
    fun workerInterruptionPausesAndPreservesCheckpointAndRunHistory() {
        val task = DurableTask(
            id = "task-interrupted",
            title = "Resumable task",
            description = "fixture",
            agentId = "research",
            input = "fixture",
            plan = listOf(TaskPlanStep(id = "step", title = "Run"))
        )
        val interrupted = task
            .beginRun("run-1", "step", nowMs = 1_000L)
            .updateStep(
                stepId = "step",
                checkpointData = "checkpoint-1",
                progressPercent = 45,
                nowMs = 1_500L
            )
            .pauseAfterWorkerStop("Worker stopped by the platform", nowMs = 2_000L)

        assertEquals(DurableTaskStatus.PAUSED, interrupted.status)
        assertEquals(TaskPauseReason.WORKER_INTERRUPTED, interrupted.pauseReason)
        assertTrue(!interrupted.isTerminal)
        assertEquals("checkpoint-1", interrupted.checkpointData)
        assertEquals("step", interrupted.currentStepId)
        assertEquals("run-1", interrupted.plan.single().runId)
        assertEquals(TaskStepStatus.PAUSED, interrupted.plan.single().status)
        assertEquals(TaskRunStatus.PAUSED, interrupted.runs.single().status)
        assertEquals(2_000L, interrupted.runs.single().finishedAtMs)
        assertEquals("Worker stopped by the platform", interrupted.runs.single().error)
        assertEquals(1, interrupted.runs.size)
        assertEquals(-1L, interrupted.finishedAtMs)
    }

    @Test
    fun explicitCancellationRemainsTerminalAndCannotBeResumedAsRetry() {
        val cancelled = DurableTask(
            id = "task-cancelled",
            title = "Cancelled task",
            description = "fixture",
            agentId = "research",
            input = "fixture"
        ).beginRun("run-1", nowMs = 1_000L).cancel(nowMs = 2_000L)

        assertEquals(DurableTaskStatus.CANCELLED, cancelled.status)
        assertTrue(cancelled.isTerminal)
        assertEquals(TaskRunStatus.CANCELLED, cancelled.runs.single().status)
        assertNull(cancelled.pauseReason)
    }
}
