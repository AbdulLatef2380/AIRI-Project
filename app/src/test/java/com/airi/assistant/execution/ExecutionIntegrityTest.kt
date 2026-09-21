package com.airi.assistant.execution

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExecutionIntegrityTest {
    @Test
    fun identityKeepsSessionAndBuildsDistinctToolCalls() {
        val identity = ExecutionIdentity(sessionId = "session-1")
        val first = identity.childToolCallId("memory_recall", 1)
        val second = identity.childToolCallId("memory_recall", 2)
        assertTrue(identity.requestId.startsWith("req-"))
        assertTrue(identity.executionId.startsWith("exec-"))
        assertTrue(first != second)
        assertTrue(first.contains(identity.executionId))
    }

    @Test
    fun stateMachineAllowsNormalLifecycleAndExactlyOneTerminalState() {
        val machine = ExecutionStateMachine()
        assertTrue(machine.transition(ExecutionLifecycleState.PREPARING))
        assertTrue(machine.transition(ExecutionLifecycleState.VALIDATING))
        assertTrue(machine.transition(ExecutionLifecycleState.PREPARING_CONTEXT))
        assertTrue(machine.transition(ExecutionLifecycleState.EXECUTING))
        assertTrue(machine.transition(ExecutionLifecycleState.STREAMING))
        assertTrue(machine.terminal(ExecutionLifecycleState.COMPLETED))
        assertEquals(ExecutionLifecycleState.COMPLETED, machine.state)
        assertFalse(machine.terminal(ExecutionLifecycleState.FAILED))
        assertFalse(machine.transition(ExecutionLifecycleState.EXECUTING))
    }

    @Test
    fun stateMachineRejectsIllegalSkip() {
        val machine = ExecutionStateMachine()
        assertFalse(machine.transition(ExecutionLifecycleState.STREAMING))
        assertEquals(ExecutionLifecycleState.IDLE, machine.state)
    }

    @Test
    fun terminalDeliveryIsExactlyOnce() {
        val guard = TerminalDeliveryGuard()
        assertTrue(guard.tryDeliver())
        assertFalse(guard.tryDeliver())
        assertTrue(guard.wasDelivered())
    }

    @Test
    fun toolLedgerBlocksOnlyExactDuplicateAndAllowsChangedArguments() {
        val ledger = ToolCallLedger()
        val first = ToolCallFingerprint("exec-1", "memory_recall", "hash-a", "step-1")
        val changedArgs = first.copy(argumentsHash = "hash-b")
        val changedStep = first.copy(parentStepId = "step-2")

        assertTrue(ledger.shouldExecute(first))
        assertTrue(ledger.markCompleted(first))
        assertFalse(ledger.shouldExecute(first))
        assertTrue(ledger.shouldExecute(changedArgs))
        assertTrue(ledger.shouldExecute(changedStep))
        ledger.clearExecution("exec-1")
        assertTrue(ledger.shouldExecute(first))
    }
}
