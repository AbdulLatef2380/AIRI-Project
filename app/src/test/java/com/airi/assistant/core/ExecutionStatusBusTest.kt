package com.airi.assistant.core

import com.airi.assistant.ui.viewmodel.ExecutionStage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

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
    fun toolTraceIncludesRequestOutputAndVerificationEvidence() {
        val executionId = "typed-tool-lifecycle-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Verify workspace", 1, executionId)
        ExecutionStatusBus.onToolRequested(
            executionId = executionId,
            actionId = "tool-1",
            toolName = "read_file",
            inputParams = mapOf("path" to "/safe/README.md"),
            parentId = "step-1",
        )
        ExecutionStatusBus.onToolStarted(
            executionId = executionId,
            actionId = "tool-1",
            toolName = "read_file",
            inputParams = mapOf("path" to "/safe/README.md"),
            parentId = "step-1",
        )
        ExecutionStatusBus.onToolOutput(executionId, "tool-1", "read_file", "3 lines", provenance = "workspace")
        ExecutionStatusBus.onVerificationStarted(executionId, "tool-1")
        ExecutionStatusBus.onVerificationCompleted(executionId, "tool-1", passed = true, detail = "schema valid")
        ExecutionStatusBus.onToolCompleted(executionId, "tool-1", "read_file", 12L, "3 lines")

        val events = ExecutionStatusBus.traceSnapshot(executionId)
        assertTrue(events.any { it.kind == ExecutionTraceKind.TOOL_REQUESTED && it.status == ExecutionTraceStatus.PENDING })
        assertTrue(events.any { it.kind == ExecutionTraceKind.TOOL_OUTPUT && it.outputSummary == "3 lines" })
        assertTrue(events.any { it.kind == ExecutionTraceKind.VERIFICATION_STARTED })
        assertTrue(events.any { it.kind == ExecutionTraceKind.VERIFICATION_PASSED && it.status == ExecutionTraceStatus.COMPLETED })
        ExecutionStatusBus.reset()
    }

    @Test
    fun approvalWaitingClosesStartedToolAndRejectsLateCompletion() {
        val executionId = "approval-tool-lifecycle-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Create calendar proposal", 1, executionId)
        ExecutionStatusBus.onWaveStarted(listOf("step-1"), listOf("calendar_create"), executionId)
        ExecutionStatusBus.onToolStarted(
            executionId = executionId,
            actionId = "tool-1",
            toolName = "calendar_create",
            inputParams = mapOf("title" to "Review"),
            parentId = "step-1",
        )

        ExecutionStatusBus.onApprovalWaiting(executionId, "tool-1", "approval-1", "Create event")
        ExecutionStatusBus.onToolCompleted(executionId, "tool-1", "calendar_create", 3L)

        val events = ExecutionStatusBus.traceSnapshot(executionId)
        val waiting = events.single { it.kind == ExecutionTraceKind.WAITING_FOR_APPROVAL }
        assertEquals("step-1", waiting.parentId)
        assertEquals("Review", waiting.inputParams["title"])
        assertFalse(events.any {
            it.actionId == "tool-1" &&
                it.kind in setOf(ExecutionTraceKind.TOOL_COMPLETED, ExecutionTraceKind.TOOL_FAILED)
        })
        ExecutionStatusBus.reset()
    }

    @Test
    fun openToolTraceCompletesAfterAnotherExecutionBecomesTerminal() {
        val firstExecutionId = "open-tool-execution"
        val secondExecutionId = "terminal-visible-execution"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("First execution", 1, firstExecutionId)
        ExecutionStatusBus.onToolStarted(firstExecutionId, "tool-1", "read_file")
        ExecutionStatusBus.onGraphStarted("Second execution", 1, secondExecutionId)
        ExecutionStatusBus.onGraphCompleted(true, secondExecutionId)

        ExecutionStatusBus.onToolCompleted(firstExecutionId, "tool-1", "read_file", 5L)

        assertEquals(
            1,
            ExecutionStatusBus.traceSnapshot(firstExecutionId).count {
                it.kind == ExecutionTraceKind.TOOL_COMPLETED && it.actionId == "tool-1"
            },
        )
        assertEquals(secondExecutionId, ExecutionStatusBus.status.value.executionId)
        assertEquals(ExecutionStage.COMPLETED, ExecutionStatusBus.status.value.executionStage)
        ExecutionStatusBus.reset()
    }

    @Test
    fun approvalTraceForOpenNonCurrentExecutionDoesNotReplaceVisibleStatus() {
        val firstExecutionId = "approval-background-execution"
        val visibleExecutionId = "approval-visible-execution"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Approval owner", 1, firstExecutionId)
        ExecutionStatusBus.onToolStarted(firstExecutionId, "tool-approval", "calendar_create")
        ExecutionStatusBus.onGraphStarted("Visible execution", 1, visibleExecutionId)

        ExecutionStatusBus.onApprovalWaiting(
            firstExecutionId,
            "tool-approval",
            "approval-background",
            "Create calendar event",
        )

        assertTrue(ExecutionStatusBus.traceSnapshot(firstExecutionId).any {
            it.kind == ExecutionTraceKind.WAITING_FOR_APPROVAL && it.actionId == "tool-approval"
        })
        assertEquals(visibleExecutionId, ExecutionStatusBus.status.value.executionId)
        assertFalse(ExecutionStatusBus.status.value.waitingForApproval)
        ExecutionStatusBus.reset()
    }

    @Test
    fun backgroundApprovalCancellationClosesOnlyItsOwnedTrace() {
        val approvalExecutionId = "approval-cancel-background"
        val visibleExecutionId = "approval-cancel-visible"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Approval task", 1, approvalExecutionId)
        ExecutionStatusBus.onToolStarted(approvalExecutionId, "cancel-tool", "calendar_create")
        ExecutionStatusBus.onApprovalWaiting(
            approvalExecutionId,
            "cancel-tool",
            "cancel-interrupt",
            "Create calendar event",
        )
        ExecutionStatusBus.onGraphStarted("Visible task", 1, visibleExecutionId)

        ExecutionStatusBus.onApprovalCancelled(
            approvalExecutionId,
            "cancel-tool",
            "cancel-interrupt",
            "User declined",
        )

        val trace = ExecutionStatusBus.traceSnapshot(approvalExecutionId)
        assertEquals(ExecutionTraceKind.APPROVAL_CANCELLED, trace.last().kind)
        assertEquals(visibleExecutionId, ExecutionStatusBus.status.value.executionId)
        ExecutionStatusBus.onToolCompleted(approvalExecutionId, "cancel-tool", "calendar_create", 1L)
        assertEquals(ExecutionTraceKind.APPROVAL_CANCELLED, ExecutionStatusBus.traceSnapshot(approvalExecutionId).last().kind)
        ExecutionStatusBus.reset()
    }

    @Test
    fun backgroundApprovalTimeoutClosesOnlyItsOwnedTrace() {
        val approvalExecutionId = "approval-timeout-background"
        val visibleExecutionId = "approval-timeout-visible"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Approval task", 1, approvalExecutionId)
        ExecutionStatusBus.onToolStarted(approvalExecutionId, "timeout-tool", "calendar_create")
        ExecutionStatusBus.onApprovalWaiting(
            approvalExecutionId,
            "timeout-tool",
            "timeout-interrupt",
            "Create calendar event",
        )
        ExecutionStatusBus.onGraphStarted("Visible task", 1, visibleExecutionId)

        ExecutionStatusBus.onApprovalTimedOut(
            approvalExecutionId,
            "timeout-tool",
            "timeout-interrupt",
            "Expired",
        )

        val trace = ExecutionStatusBus.traceSnapshot(approvalExecutionId)
        assertEquals(ExecutionTraceKind.APPROVAL_TIMEOUT, trace.last().kind)
        assertEquals(visibleExecutionId, ExecutionStatusBus.status.value.executionId)
        ExecutionStatusBus.onToolCompleted(approvalExecutionId, "timeout-tool", "calendar_create", 1L)
        assertEquals(ExecutionTraceKind.APPROVAL_TIMEOUT, ExecutionStatusBus.traceSnapshot(approvalExecutionId).last().kind)
        ExecutionStatusBus.reset()
    }

    @Test
    fun terminalToolEventCarriesStartedInputAndParent() {
        val executionId = "trace-input-parent-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Inspect repository", 1, executionId)
        ExecutionStatusBus.onWaveStarted(listOf("step-1"), listOf("read_file"), executionId)
        ExecutionStatusBus.onToolStarted(
            executionId = executionId,
            actionId = "tool-1",
            toolName = "read_file",
            inputParams = mapOf("path" to "/safe/README.md"),
            parentId = "step-1",
        )
        ExecutionStatusBus.onToolCompleted(executionId, "tool-1", "read_file", 12L)

        val completed = ExecutionStatusBus.trace.value.single { it.kind == ExecutionTraceKind.TOOL_COMPLETED }
        assertEquals(mapOf("path" to "/safe/README.md"), completed.inputParams)
        assertEquals("step-1", completed.parentId)
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
    fun concurrentExecutionsKeepTheirOwnEventsAndTerminalState() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("First task", 1, "parallel-1", "session-1")
        ExecutionStatusBus.onGraphStarted("Second task", 1, "parallel-2", "session-1")

        ExecutionStatusBus.onWaveStarted(listOf("step-1"), listOf("read_first"), "parallel-1")
        ExecutionStatusBus.onToolStarted("parallel-1", "tool-1", "read_first")
        ExecutionStatusBus.onToolCompleted("parallel-1", "tool-1", "read_first", 4L)
        ExecutionStatusBus.onGraphCompleted(success = true, executionId = "parallel-1")

        ExecutionStatusBus.onWaveStarted(listOf("step-2"), listOf("read_second"), "parallel-2")
        ExecutionStatusBus.onGraphCompleted(success = false, executionId = "parallel-2")

        val first = ExecutionStatusBus.traceSnapshot("parallel-1")
        val second = ExecutionStatusBus.traceSnapshot("parallel-2")
        assertTrue(first.all { it.executionId == "parallel-1" })
        assertTrue(second.all { it.executionId == "parallel-2" })
        assertEquals(ExecutionTraceKind.COMPLETED, first.last().kind)
        assertEquals(ExecutionTraceKind.FAILED, second.last().kind)
        ExecutionStatusBus.reset()
    }

    @Test
    fun backgroundGraphTimeoutClosesItsTraceWithoutReplacingVisibleStatus() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Timed out background task", 1, "timeout-background")
        ExecutionStatusBus.onGraphStarted("Visible task", 1, "timeout-visible")

        ExecutionStatusBus.onGraphTimedOut("timeout-background", "Background deadline exceeded")

        assertEquals(ExecutionTraceKind.TIMED_OUT, ExecutionStatusBus.traceSnapshot("timeout-background").last().kind)
        assertEquals("timeout-visible", ExecutionStatusBus.status.value.executionId)
        assertTrue(ExecutionStatusBus.traceSnapshot("timeout-background").none {
            it.kind == ExecutionTraceKind.COMPLETED || it.kind == ExecutionTraceKind.FAILED
        })
        ExecutionStatusBus.reset()
    }

    @Test
    fun backgroundGraphCancellationClosesItsTraceWithoutReplacingVisibleStatus() {
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Cancelled background task", 1, "cancel-background")
        ExecutionStatusBus.onGraphStarted("Visible task", 1, "cancel-visible")

        ExecutionStatusBus.onGraphCancelled("cancel-background")

        assertEquals(ExecutionTraceKind.CANCELLED, ExecutionStatusBus.traceSnapshot("cancel-background").last().kind)
        assertEquals("cancel-visible", ExecutionStatusBus.status.value.executionId)
        ExecutionStatusBus.reset()
    }

    @Test
    fun interleavedExecutionCallbacksKeepToolLifecyclesAndTraceOwnershipIsolated() {
        ExecutionStatusBus.reset()
        val firstId = "interleaved-1"
        val secondId = "interleaved-2"
        ExecutionStatusBus.onGraphStarted("First task", 100, firstId, "session-1")
        ExecutionStatusBus.onGraphStarted("Second task", 100, secondId, "session-1")

        val startGate = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(2)
        listOf(firstId, secondId).forEach { executionId ->
            executor.submit {
                startGate.await()
                repeat(100) { index ->
                    val actionId = "$executionId-tool-$index"
                    ExecutionStatusBus.onToolStarted(executionId, actionId, "read_file")
                    ExecutionStatusBus.onToolCompleted(executionId, actionId, "read_file", 1L)
                }
                ExecutionStatusBus.onGraphCompleted(true, executionId)
            }
        }
        startGate.countDown()
        executor.shutdown()

        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        val first = ExecutionStatusBus.traceSnapshot(firstId)
        val second = ExecutionStatusBus.traceSnapshot(secondId)
        assertTrue(first.all { it.executionId == firstId })
        assertTrue(second.all { it.executionId == secondId })
        assertEquals(100, first.count { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
        assertEquals(100, second.count { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
        assertEquals(ExecutionTraceKind.COMPLETED, first.last().kind)
        assertEquals(ExecutionTraceKind.COMPLETED, second.last().kind)
        ExecutionStatusBus.reset()
    }

    @Test
    fun stressMatrix_preservesIsolationForOneTenAndFiftyExecutions() {
        val previousHistoryLimit = ExecutionStatusBus.maxTraceHistory
        try {
            // Keep every execution addressable during this test; production remains bounded at 32.
            ExecutionStatusBus.maxTraceHistory = 64
            listOf(1, 10, 50).forEach { executionCount ->
                ExecutionStatusBus.reset()
                val ids = (0 until executionCount).map { "stress-$executionCount-$it" }
                ids.forEach { executionId ->
                    ExecutionStatusBus.onGraphStarted(
                        goalDescription = "Stress execution",
                        totalNodes = 100,
                        executionId = executionId,
                        sessionId = "stress-session",
                    )
                }

                val startedAt = System.nanoTime()
                val heapBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                val executor = Executors.newFixedThreadPool(minOf(executionCount, 8))
                ids.forEach { executionId ->
                    executor.submit {
                        // 50 starts + 50 terminals = 100 tool events per execution.
                        repeat(50) { index ->
                            val actionId = "$executionId-tool-$index"
                            ExecutionStatusBus.onToolStarted(executionId, actionId, "read_file")
                            ExecutionStatusBus.onToolCompleted(executionId, actionId, "read_file", 1L)
                        }
                        ExecutionStatusBus.onGraphCompleted(true, executionId)
                    }
                }
                executor.shutdown()
                assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS))

                val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000L
                val heapAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
                println("P2 executionCount=$executionCount toolEventsPerExecution=100 elapsedMs=$elapsedMs heapDeltaBytes=${heapAfter - heapBefore}")

                ids.forEach { executionId ->
                    val events = ExecutionStatusBus.traceSnapshot(executionId)
                    assertTrue("missing trace for $executionId", events.isNotEmpty())
                    assertTrue("cross-owner event in $executionId", events.all { it.executionId == executionId })
                    assertEquals(50, events.count { it.kind == ExecutionTraceKind.TOOL_COMPLETED })
                    assertEquals(ExecutionTraceKind.COMPLETED, events.last().kind)
                    assertEquals(
                        events.map { it.sequence }.distinct().size,
                        events.size,
                    )
                    assertTrue("sequence order regressed for $executionId", events.zipWithNext().all { (left, right) -> left.sequence < right.sequence })
                }
            }
        } finally {
            ExecutionStatusBus.maxTraceHistory = previousHistoryLimit
            ExecutionStatusBus.reset()
        }
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
    fun timeoutIsTerminalDistinctFromFailureAndRejectsLateEvents() {
        val executionId = "timeout-terminal-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Long task", 1, executionId, sessionId = "session-1")

        ExecutionStatusBus.onGraphTimedOut(executionId, "stall deadline reached")

        assertFalse(ExecutionStatusBus.status.value.isWorking)
        assertEquals(ExecutionStage.FAILED, ExecutionStatusBus.status.value.executionStage)
        assertEquals(ExecutionTraceKind.TIMED_OUT, ExecutionStatusBus.trace.value.last().kind)
        assertEquals("execution_timeout", ExecutionStatusBus.trace.value.last().errorCode)
        val traceAfterTimeout = ExecutionStatusBus.trace.value
        ExecutionStatusBus.onToolStarted(executionId, "late-tool", "late_tool")
        ExecutionStatusBus.onGraphCompleted(success = true, executionId = executionId)
        assertEquals(traceAfterTimeout, ExecutionStatusBus.trace.value)
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

    @Test
    fun approvalCancelByInterruptIdPublishesTerminalTrace() {
        val executionId = "approval-cancel-live-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Write file", 1, executionId)
        ExecutionStatusBus.onApprovalWaiting(executionId, "tool-1", "approval-1", "Write file")

        ExecutionStatusBus.onApprovalCancelled("approval-1", "tool-1", "Denied by user")

        assertEquals(ExecutionStage.CANCELLED, ExecutionStatusBus.status.value.executionStage)
        assertEquals(ExecutionTraceKind.APPROVAL_CANCELLED, ExecutionStatusBus.trace.value.last().kind)
        ExecutionStatusBus.reset()
    }

    @Test
    fun approvalTerminalOutcomesRejectLateToolsAndDuplicateFinalEvents() {
        val executionId = "approval-terminal-admission-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Publish", 1, executionId)
        ExecutionStatusBus.onApprovalWaiting(executionId, "tool-1", "approval-1", "Publish")
        ExecutionStatusBus.onApprovalCancelled("approval-1", "tool-1", "Denied")

        val afterCancel = ExecutionStatusBus.trace.value
        ExecutionStatusBus.onToolStarted(executionId, "late-tool", "late_tool")
        ExecutionStatusBus.onGraphCompleted(success = true, executionId = executionId)

        assertEquals(afterCancel, ExecutionStatusBus.trace.value)
        assertEquals(1, ExecutionStatusBus.trace.value.count { it.kind == ExecutionTraceKind.APPROVAL_CANCELLED })
        ExecutionStatusBus.reset()
    }

    @Test
    fun graphCompletionCannotOverwriteApprovalWaitingState() {
        val executionId = "approval-completion-race-test"
        ExecutionStatusBus.reset()
        ExecutionStatusBus.onGraphStarted("Create event", 1, executionId)
        ExecutionStatusBus.onApprovalWaiting(executionId, "tool-1", "approval-1", "Create event")

        ExecutionStatusBus.onGraphCompleted(success = true, executionId = executionId)

        assertTrue(ExecutionStatusBus.status.value.waitingForApproval)
        assertEquals(ExecutionTraceKind.WAITING_FOR_APPROVAL, ExecutionStatusBus.trace.value.last().kind)
        ExecutionStatusBus.reset()
    }
}
