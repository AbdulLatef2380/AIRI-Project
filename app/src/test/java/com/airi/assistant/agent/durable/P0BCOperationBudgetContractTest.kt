package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class P0BCOperationBudgetContractTest {
    @Test fun stableIdentityAndCasPreventDuplicateProviderEffects() {
        val ledger = OperationLedger()
        val key = OperationIdentity.idempotencyKey("t", "r", "s", "write")
        val hash = OperationIdentity.payloadHash(mapOf("b" to "2", "a" to "1"))
        assertEquals(hash, OperationIdentity.payloadHash(mapOf("a" to "1", "b" to "2")))
        val prepared = ledger.prepare(LedgerRecord("op", "t", "r", "s", "write", key, hash, "fake"))!!
        assertNull(ledger.prepare(LedgerRecord("op-2", "t", "r", "s", "write", key, hash, "fake")))
        val claimed = ledger.cas("op", prepared.version, DurableOperationStatus.PREPARED, DurableOperationStatus.CLAIMED)!!
        assertNull(ledger.cas("op", prepared.version, DurableOperationStatus.PREPARED, DurableOperationStatus.CLAIMED))
        assertEquals(2L, claimed.version)
        val connector = FakeIdempotentConnector()
        val first = connector.execute(key, hash)
        val second = connector.execute(key, hash)
        assertEquals(first, second)
        assertEquals(1, connector.invocationCount)
    }

    @Test fun acceptedThenLostResponseIsUnknownAndNeverAutoReplayed() {
        val connector = FakeIdempotentConnector(failAfterAcceptance = true)
        val first = connector.execute("stable-key", "a".repeat(64))
        assertEquals(OperationOutcome.UNKNOWN, first.outcome)
        val reconciled = connector.execute("stable-key", "a".repeat(64))
        assertEquals(OperationOutcome.UNKNOWN, reconciled.outcome)
        assertEquals(1, connector.invocationCount)
    }

    @Test fun fakeClockAndTrackerPreserveUsageAcrossChecks() {
        var now = 10L
        val tracker = ExecutionBudgetTracker(
            ExecutionBudget(decisionCycles = 2, toolOperations = 1, retries = 1, runDeadlineMs = 100L),
            ExecutionClock { now },
        )
        assertNull(tracker.beforeDecision())
        assertEquals(1, tracker.usage.decisionCycles)
        assertNull(tracker.beforeTool())
        assertEquals(1, tracker.usage.toolOperations)
        assertEquals(BudgetPauseReason.RUN_BUDGET_EXHAUSTED, tracker.beforeTool())
        now = 100L
        assertEquals(BudgetPauseReason.RUN_BUDGET_EXHAUSTED, tracker.check())
    }

    @Test fun budgetsRemainIndependentAndPauseReasonsAreTyped() {
        val budget = ExecutionBudget(decisionCycles = 3, toolOperations = 20, retries = 20, tokens = 100, costMicros = 500, taskDeadlineMs = 10_000, runDeadlineMs = 5_000)
        assertEquals(BudgetPauseReason.RUN_BUDGET_EXHAUSTED, budget.guard(ExecutionUsage(decisionCycles = 3), 0))
        assertEquals(BudgetPauseReason.TASK_TOKEN_BUDGET_EXHAUSTED, budget.guard(ExecutionUsage(tokens = 100), 0))
        assertEquals(BudgetPauseReason.TASK_COST_BUDGET_EXHAUSTED, budget.guard(ExecutionUsage(costMicros = 500), 0))
        assertEquals(BudgetPauseReason.TASK_DEADLINE_REACHED, budget.guard(ExecutionUsage(), 10_000))
        assertEquals(BudgetPauseReason.RUN_BUDGET_EXHAUSTED, budget.guard(ExecutionUsage(), 5_000))
        assertTrue(ProgressWatchdog(100).run {
            markProgress(1_000); !isStalled(1_099) && isStalled(1_100) && !isStalled(10_000, externalPending = true)
        })
    }

    @Test fun approvalDecisionIsOneWayAndRevokeBlocksContinuation() {
        val approval = TaskApproval("a", action = "write", description = "write", riskLevel = "HIGH", expiresAtMs = 10_000, runId = "r", stepId = "s")
        val task = DurableTask(id = "t", title = "t", description = "t", agentId = "a", input = "", plan = listOf(TaskPlanStep("s", "s")))
            .beginRun("r", "s", 1)
            .requestApproval(approval)
        val revoked = task.revokeApproval("a", nowMs = 2)
        assertEquals(TaskApprovalStatus.REVOKED, revoked.approvals.single().status)
        assertEquals(revoked, revoked.decideApproval("a", TaskApprovalStatus.APPROVED, ApprovalGrantScope.ONCE, nowMs = 3))
    }
}
