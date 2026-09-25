package com.airi.assistant.execution

import com.airi.assistant.core.ExecutionTraceEvent
import com.airi.assistant.core.ExecutionTraceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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
    fun chatViewModelAndAgentLoopShareTheSameNormalizedSessionIdentity() {
        val chatSessionId = ChatExecutionIdentityContract.normalizeSessionId("  chat-session-42  ")
        val identity = ChatExecutionIdentityContract.create(chatSessionId, "execution-42")

        assertEquals("chat-session-42", chatSessionId)
        assertEquals("chat-session-42", identity.sessionId)
        assertEquals("execution-42", identity.executionId)
    }

    @Test
    fun blankChatSessionUsesSafeNonBlankBoundaryValue() {
        val identity = ChatExecutionIdentityContract.create("  ", "execution-blank-session")

        assertEquals(ChatExecutionIdentityContract.UNKNOWN_SESSION, identity.sessionId)
        assertTrue(identity.sessionId.isNotBlank())
    }

    @Test
    fun retryRequestPreservesIdentityWhilePromptMayChange() {
        val identity = ChatExecutionIdentityContract.create("chat-session-7", "execution-7")
        val firstRequest = ExecutionRequest(
            prompt = "first",
            requestedModelId = "model-authoritative-1",
            identity = identity,
        )
        val retryRequest = firstRequest.copy(prompt = "retry with reduced context")

        assertEquals(identity, firstRequest.identity)
        assertEquals(identity, retryRequest.identity)
        assertEquals("model-authoritative-1", retryRequest.requestedModelId)
        assertEquals("retry with reduced context", retryRequest.prompt)
    }

    @Test
    fun backendBoundaryResolvesIdentityForLegacyAnonymousRequests() {
        val request = ExecutionRequest(
            prompt = "hello",
            sessionTag = "  session-from-legacy-call  ",
        )

        val resolved = request.withResolvedIdentity()

        assertTrue(resolved.identity != null)
        assertEquals("session-from-legacy-call", resolved.identity?.sessionId)
        assertTrue(resolved.identity?.requestId?.startsWith("req-") == true)
        assertTrue(resolved.identity?.executionId?.startsWith("exec-") == true)
    }

    @Test
    fun correlationIdsAreStableAndScopedToExecution() {
        val event = ExecutionTraceEvent(
            executionId = "exec-events",
            sequence = 1L,
            timestampMs = 100L,
            kind = ExecutionTraceKind.COMPLETED,
            summary = "done",
        )

        assertEquals("exec-events:event:1", event.eventId)
        assertEquals("exec-events:delivery:1", event.deliveryId)
        assertTrue(event.eventId != event.deliveryId)
        assertTrue(event.eventId.startsWith(event.executionId))
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
    fun cancellationIsTerminalAndCannotBeReplacedBySuccessOrFailure() {
        val machine = ExecutionStateMachine()
        assertTrue(machine.transition(ExecutionLifecycleState.PREPARING))
        assertTrue(machine.transition(ExecutionLifecycleState.VALIDATING))
        assertTrue(machine.terminal(ExecutionLifecycleState.CANCELLED))
        assertFalse(machine.terminal(ExecutionLifecycleState.COMPLETED))
        assertFalse(machine.terminal(ExecutionLifecycleState.FAILED))
        assertEquals(ExecutionLifecycleState.CANCELLED, machine.state)
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
