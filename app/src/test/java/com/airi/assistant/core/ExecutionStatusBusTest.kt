package com.airi.assistant.core

import com.airi.assistant.ui.viewmodel.ExecutionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutionStatusBusTest {

    @Test
    fun eventsWithoutExplicitIdentityAreRejected() {
        assertFalse(ExecutionStatusBus.acceptsEvent("execution-a", ""))
        assertFalse(ExecutionStatusBus.acceptsEvent("", "execution-a"))
    }

    @Test
    fun eventsFromAnotherExecutionAreRejected() {
        assertFalse(ExecutionStatusBus.acceptsEvent("execution-a", "execution-b"))
        assertTrue(ExecutionStatusBus.acceptsEvent("execution-a", "execution-a"))
    }

    @Test
    fun statusFromAnotherOrUnscopedSessionIsRejected() {
        assertFalse(ExecutionStatusBus.belongsToSession("session-a", "session-b"))
        assertFalse(ExecutionStatusBus.belongsToSession("session-a", ""))
        assertFalse(ExecutionStatusBus.belongsToSession("", "session-a"))
        assertTrue(ExecutionStatusBus.belongsToSession("session-a", "session-a"))
    }

    @Test
    fun agentLoopStepLifecycleUsesOneStableActionIdAndMachineTags() {
        val executionId = "tree-integration-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Review repository", 1, executionId)
        ExecutionStatusBus.onWaveStarted(listOf("step-1"), listOf("read_file"), executionId)
        ExecutionStatusBus.onToolStarted(executionId, "tool-1", "read_file")
        ExecutionStatusBus.onToolCompleted(executionId, "tool-1", "read_file", 12L)
        ExecutionStatusBus.onNodeCompleted("step-1", 1, executionId)

        val stepEvents = ExecutionStatusBus.trace.value.filter {
            it.kind == ExecutionTraceKind.STEP_STARTED || it.kind == ExecutionTraceKind.STEP_COMPLETED
        }
        assertEquals(listOf("step-1", "step-1"), stepEvents.map { it.actionId })
        assertEquals("step_started", stepEvents[0].machineTag)
        assertEquals("step_completed", stepEvents[1].machineTag)
        assertEquals("tool_call", ExecutionStatusBus.trace.value.first { it.actionId == "tool-1" }.machineTag)
        ExecutionStatusBus.reset()
    }

    @Test
    fun traceSnapshotRemainsBoundToItsExecutionAfterAnotherRunStarts() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("First task", 1, "trace-run-1", "session-1")
        ExecutionStatusBus.onWaveStarted(listOf("step-1"), listOf("read_file"), "trace-run-1")
        val firstSnapshot = ExecutionStatusBus.traceSnapshot("trace-run-1")
        assertTrue(firstSnapshot.isNotEmpty())

        ExecutionStatusBus.onGraphStarted("Second task", 1, "trace-run-2", "session-1")

        assertEquals(firstSnapshot, ExecutionStatusBus.traceSnapshot("trace-run-1"))
        assertTrue(ExecutionStatusBus.trace.value.all { it.executionId == "trace-run-2" })
        assertTrue(ExecutionStatusBus.traceSnapshot("trace-run-2").isNotEmpty())
        ExecutionStatusBus.reset()
    }

    @Test
    fun cancellationRequestClosesAdmissionBeforeTerminalCancellation() {
        val executionId = "cancellation-gate-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Long task", 2, executionId, sessionId = "session-1")

        ExecutionStatusBus.onGraphCancellationRequested(executionId)
        ExecutionStatusBus.onGraphCancellationRequested(executionId)

        assertTrue(ExecutionStatusBus.status.value.cancellationRequested)
        assertEquals("Stopping…", ExecutionStatusBus.status.value.currentAction)
        val traceBeforeLateEvent = ExecutionStatusBus.trace.value
        ExecutionStatusBus.onToolStarted(executionId, "late-tool", "late_tool")
        ExecutionStatusBus.onGraphCompleted(success = true, executionId = executionId)

        assertEquals(traceBeforeLateEvent, ExecutionStatusBus.trace.value)
        assertTrue(ExecutionStatusBus.status.value.isWorking)

        ExecutionStatusBus.onGraphCancelled(executionId)

        assertFalse(ExecutionStatusBus.status.value.isWorking)
        assertEquals(ExecutionStage.CANCELLED, ExecutionStatusBus.status.value.executionStage)
        assertFalse(ExecutionStatusBus.status.value.cancellationRequested)
        assertEquals(1, ExecutionStatusBus.trace.value.count { it.kind == ExecutionTraceKind.CANCELLED })
        ExecutionStatusBus.reset()
    }

    @Test
    fun startedToolStillPublishesCancellationAfterStopRequest() {
        val executionId = "tool-cancel-after-stop-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Long tool", 1, executionId)
        ExecutionStatusBus.onToolStarted(executionId, "active-tool", "active_tool")
        ExecutionStatusBus.onGraphCancellationRequested(executionId)

        ExecutionStatusBus.onToolCancelled(
            executionId = executionId,
            actionId = "active-tool",
            toolName = "active_tool",
            durationMs = 25L,
        )

        assertEquals(1, ExecutionStatusBus.trace.value.count { it.kind == ExecutionTraceKind.TOOL_CANCELLED })
        assertEquals("active-tool", ExecutionStatusBus.trace.value.last().actionId)
        ExecutionStatusBus.onGraphCancelled(executionId)
        ExecutionStatusBus.reset()
    }

    @Test
    fun approvalWaitingIsKeyedAndResumesOnlyWithTheSameInterrupt() {
        val executionId = "approval-wait-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Send report", 1, executionId)
        ExecutionStatusBus.onApprovalWaiting(executionId, "tool-1", "approval-1", "Send report", 10_000L)

        assertFalse(ExecutionStatusBus.status.value.isWorking)
        assertTrue(ExecutionStatusBus.status.value.waitingForApproval)
        assertEquals("approval-1", ExecutionStatusBus.status.value.approvalInterruptId)
        assertEquals(ExecutionTraceKind.WAITING_FOR_APPROVAL, ExecutionStatusBus.trace.value.last().kind)

        ExecutionStatusBus.onApprovalResumed(executionId, "wrong-approval")
        assertTrue(ExecutionStatusBus.status.value.waitingForApproval)
        ExecutionStatusBus.onApprovalResumed(executionId, "approval-1")
        assertTrue(ExecutionStatusBus.status.value.isWorking)
        assertFalse(ExecutionStatusBus.status.value.waitingForApproval)
        ExecutionStatusBus.reset()
    }

    @Test
    fun approvalTimeoutIsTerminalAndRejectsLateCompletion() {
        val executionId = "approval-timeout-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Publish", 1, executionId)
        ExecutionStatusBus.onApprovalWaiting(executionId, "tool-1", "approval-1", "Publish")
        ExecutionStatusBus.onApprovalTimedOut(executionId, "tool-1", "approval-1")

        assertEquals(ExecutionStage.FAILED, ExecutionStatusBus.status.value.executionStage)
        assertEquals(ExecutionTraceKind.APPROVAL_TIMEOUT, ExecutionStatusBus.trace.value.last().kind)
        val traceAfterTimeout = ExecutionStatusBus.trace.value
        ExecutionStatusBus.onGraphCompleted(success = true, executionId = executionId)
        assertEquals(traceAfterTimeout, ExecutionStatusBus.trace.value)
        ExecutionStatusBus.reset()
    }
}
