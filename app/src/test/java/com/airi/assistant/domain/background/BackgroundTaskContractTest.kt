package com.airi.assistant.domain.background

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundTaskContractTest {
    @Test
    fun workNamesAreStableAndPayloadIndependent() {
        assertEquals("airi_background_agent", BackgroundWorkNames.AGENT)
        assertEquals("airi_cloud_sync", BackgroundWorkNames.CLOUD_SYNC)
        assertEquals("system_sandbox_reaper", BackgroundWorkNames.MAINTENANCE_SANDBOX_REAPER)
        assertEquals("durable_task_task-1", BackgroundWorkNames.durableTask("task-1"))
    }

    @Test
    fun retryIsNonTerminalAndBounded() {
        val queued = BackgroundTaskRecord(taskId = "task-1", maxAttempts = 2)
        val running = queued.begin(10L)!!
        val retrying = running.retry(20L)!!
        assertEquals(1, retrying.attempt)
        assertEquals(BackgroundTaskState.RETRYING, retrying.state)
        assertEquals(BackgroundTaskState.COMPLETED, retrying.terminal(BackgroundTaskState.COMPLETED, 30L).state)
        val second = retrying.copy(state = BackgroundTaskState.RUNNING).begin(40L)
        assertEquals(2, second!!.attempt)
        assertTrue(second.terminal(BackgroundTaskState.FAILED, 50L).state == BackgroundTaskState.FAILED)
        assertNull(second.retry(60L))
    }

    @Test
    fun terminalTransitionRejectsNonTerminalStates() {
        val record = BackgroundTaskRecord(taskId = "task-2")
        try {
            record.terminal(BackgroundTaskState.RUNNING, 1L)
            throw AssertionError("Expected terminal state validation")
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}
