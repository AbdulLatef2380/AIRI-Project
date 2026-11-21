package com.airi.assistant.agent.durable

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Device-level recovery harness for the boundary that cannot be proven by JVM
 * tests: Room state is reloaded by a new manager after the old process owner
 * disappears. The test deliberately does not kill its own instrumentation
 * process; recreating the durable owner models the same persisted boundary.
 */
@RunWith(AndroidJUnit4::class)
class DurableTaskProcessDeathTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun runningTaskIsPausedOnceAfterOwnerRecreationAndIsNotReenqueued() {
        val taskId = "process-death-${UUID.randomUUID()}"
        val task = DurableTask(
            id = taskId,
            title = "Process death fixture",
            description = "checkpoint recovery",
            agentId = "fixture-agent",
            input = "continue safely",
            plan = listOf(TaskPlanStep(id = "agent-loop", title = "Agent loop"))
        )

        val firstOwner = DurableTaskManager(context)
        firstOwner.registerInProcess(task)
        assertTrue(firstOwner.beginRun(taskId, "run-before-death", "agent-loop"))
        firstOwner.updateCheckpoint(taskId, "checkpoint-before-death", 42, "checkpoint persisted")
        firstOwner.close()

        val recoveredOwner = DurableTaskManager(context)
        val paused = recoveredOwner.getTask(taskId)
        assertNotNull(paused)
        assertEquals(DurableTaskStatus.PAUSED, paused?.status)
        assertEquals(TaskPauseReason.WORKER_INTERRUPTED, paused?.pauseReason)
        assertEquals("checkpoint-before-death", paused?.checkpointData)
        assertEquals(1, paused?.runs?.size)
        assertFalse(
            WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(DurableTaskManager.workName(taskId))
                .get()
                .any { !it.state.isFinished }
        )
        recoveredOwner.close()

        // A second startup reconciliation is idempotent: it neither adds a run
        // nor changes the already-paused recovery evidence.
        val secondRecovery = DurableTaskManager(context)
        val pausedAgain = secondRecovery.getTask(taskId)
        assertEquals(paused?.stateVersion, pausedAgain?.stateVersion)
        assertEquals(paused?.runs, pausedAgain?.runs)
        assertEquals(paused?.checkpointData, pausedAgain?.checkpointData)
        secondRecovery.cancel(taskId)
        secondRecovery.close()
    }
}
