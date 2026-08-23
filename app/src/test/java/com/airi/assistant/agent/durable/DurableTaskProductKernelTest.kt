package com.airi.assistant.agent.durable

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableTaskProductKernelTest {

    @Test
    fun runLifecycleKeepsProjectOwnershipAndStepHistory() {
        val createdAt = 1_000L
        val task = DurableTask(
            id = "task-1",
            projectId = "project-a",
            ownerId = "user-a",
            title = "Create report",
            description = "Use project files to create a report",
            agentId = "research",
            input = "Create a report",
            queuedAtMs = createdAt,
            plan = listOf(
                TaskPlanStep(id = "collect", title = "Collect sources"),
                TaskPlanStep(id = "write", title = "Write report")
            ),
            memoryScope = TaskScope.PROJECT,
            knowledgeScope = TaskScope.PROJECT,
            executionNode = "desktop-1"
        )

        val running = task.beginRun("run-1", "collect", nowMs = 2_000L)
            .updateStep(
                stepId = "collect",
                progressPercent = 40,
                progressMessage = "Collecting sources",
                checkpointData = "source-count=3",
                nowMs = 2_100L
            )
            .completeStep("collect", nowMs = 2_200L)
            .updateStep("write", progressPercent = 70, nowMs = 2_300L)
            .completeStep("write", nowMs = 2_400L)
            .complete("Report ready", nowMs = 2_500L)

        assertEquals("project-a", running.projectId)
        assertEquals("user-a", running.ownerId)
        assertEquals(TaskScope.PROJECT, running.memoryScope)
        assertEquals(TaskScope.PROJECT, running.knowledgeScope)
        assertEquals("desktop-1", running.executionNode)
        assertEquals("run-1", running.currentRunId)
        assertEquals("write", running.currentStepId)
        assertEquals(DurableTaskStatus.COMPLETED, running.status)
        assertEquals(TaskRunStatus.COMPLETED, running.runs.single().status)
        assertEquals(TaskStepStatus.COMPLETED, running.plan[0].status)
        assertEquals(TaskStepStatus.COMPLETED, running.plan[1].status)
        assertEquals(100, running.progressPercent)
        assertTrue(running.isTerminal)
    }

    @Test
    fun timelineRetainsRunAndStepEvidenceWithinBound() {
        var task = sampleTask().beginRun("run-replay", "collect", nowMs = 2_000L)
        repeat(405) { index ->
            task = task.appendTimeline(
                TaskTimelineEvent(
                    type = TaskTimelineEventType.STEP_PROGRESS,
                    summary = "Progress $index",
                    runId = "run-replay",
                    stepId = "collect",
                    recordedAtMs = 2_001L + index
                )
            )
        }

        assertEquals(400, task.timeline.size)
        assertEquals("Progress 5", task.timeline.first().summary)
        assertEquals("run-replay", task.timeline.last().runId)
        assertEquals("collect", task.timeline.last().stepId)
    }

    @Test
    fun approvalDecisionRemainsBoundToTaskRunAndStep() {
        val approval = TaskApproval(
            id = "approval-1",
            action = "write_file",
            description = "Write the reviewed project file",
            riskLevel = "HIGH",
            requestedAtMs = 2_000L,
            expiresAtMs = 10_000L,
            runId = "run-approval",
            stepId = "collect"
        )
        val decided = sampleTask()
            .beginRun("run-approval", "collect", nowMs = 1_500L)
            .requestApproval(approval)
            .decideApproval(
                approvalId = "approval-1",
                status = TaskApprovalStatus.APPROVED,
                scope = ApprovalGrantScope.TASK,
                reason = "User reviewed the target",
                nowMs = 2_500L
            )

        assertEquals(listOf("approval-1"), decided.approvalIds)
        assertEquals(TaskApprovalStatus.APPROVED, decided.approvals.single().status)
        assertEquals(ApprovalGrantScope.TASK, decided.approvals.single().grantScope)
        assertEquals("run-approval", decided.approvals.single().runId)
        assertEquals("collect", decided.approvals.single().stepId)
    }

    @Test
    fun approvalContinuationPausesAndClaimsExactlyOneMatchingStep() {
        val approval = TaskApproval(
            id = "approval-resume",
            action = "github_create_issue",
            description = "Create GitHub issue",
            riskLevel = "HIGH",
            requestedAtMs = 2_000L,
            expiresAtMs = 10_000L,
            runId = "run-resume",
            stepId = "collect"
        )
        val continuation = ApprovalContinuation(
            id = "continuation-1",
            approvalId = approval.id,
            taskId = "task-sample",
            missionId = "task-sample",
            projectId = "project-a",
            runId = "run-resume",
            stepId = "collect",
            invocation = ResumableConnectorInvocation(
                connectorId = "github",
                action = "create_issue",
                text = "Document the approved change",
                params = mapOf("repo" to "org/repo", "body" to "Reviewed body"),
                idempotencyKey = "idempotency-1"
            ),
            expiresAtMs = 10_000L
        )

        val paused = sampleTask()
            .beginRun("run-resume", "collect", nowMs = 1_500L)
            .requestApproval(approval)
            .pauseForApproval(approval.id, continuation, nowMs = 2_100L)
            ?: throw AssertionError("Expected matching continuation to pause the task")

        assertEquals(DurableTaskStatus.PAUSED, paused.status)
        assertEquals(TaskRunStatus.PAUSED, paused.runs.single().status)
        assertEquals(TaskStepStatus.PAUSED, paused.plan.single().status)
        assertEquals(ApprovalContinuationStatus.PENDING, paused.approvalContinuations.single().status)
        assertTrue(MissionKernel.validate(MissionKernel.normalize(paused)) is MissionOwnershipValidation.Valid)

        val approved = paused.decideApproval(
            approvalId = approval.id,
            status = TaskApprovalStatus.APPROVED,
            scope = ApprovalGrantScope.ONCE,
            nowMs = 2_200L
        )
        val claimedPair = approved.claimApprovedContinuation(approval.id, nowMs = 2_300L)
            ?: throw AssertionError("Expected approved continuation to claim once")
        val resumed = claimedPair.first
        val claimed = claimedPair.second

        assertEquals(ApprovalContinuationStatus.CLAIMED, claimed.status)
        assertEquals(DurableTaskStatus.RUNNING, resumed.status)
        assertEquals(TaskRunStatus.RUNNING, resumed.runs.single().status)
        assertEquals(TaskStepStatus.RUNNING, resumed.plan.single().status)
        assertEquals(null, resumed.claimApprovedContinuation(approval.id, nowMs = 2_301L))
    }

    @Test
    fun artifactReferenceAttachesToTheRunningProjectStep() {
        val running = sampleTask()
            .beginRun("run-artifact", "collect", nowMs = 1_000L)
            .updateStep("collect", progressPercent = 20, nowMs = 1_100L)
            .linkArtifact("artifact-1", nowMs = 1_200L)

        assertEquals(listOf("artifact-1"), running.artifactIds)
        assertEquals(TaskStepStatus.RUNNING, running.plan.single().status)
        assertEquals("run-artifact", running.plan.single().runId)
        assertTrue(MissionKernel.validate(MissionKernel.normalize(running)) is MissionOwnershipValidation.Valid)
    }

    @Test
    fun continuationRejectsProjectMismatchAndSecretLikePayload() {
        val approval = TaskApproval(
            id = "approval-boundary",
            action = "github_create_issue",
            description = "Create GitHub issue",
            riskLevel = "HIGH",
            expiresAtMs = 10_000L,
            runId = "run-boundary",
            stepId = "collect"
        )
        val base = sampleTask().beginRun("run-boundary", "collect", nowMs = 1_000L).requestApproval(approval)
        val projectMismatch = ApprovalContinuation(
            approvalId = approval.id,
            projectId = "project-b",
            runId = "run-boundary",
            stepId = "collect",
            invocation = ResumableConnectorInvocation(
                connectorId = "github",
                action = "create_issue",
                idempotencyKey = "idempotency-project-mismatch"
            ),
            expiresAtMs = 10_000L
        )
        val secretPayload = projectMismatch.copy(
            projectId = "project-a",
            invocation = projectMismatch.invocation.copy(
                text = "Authorization: Bearer 12345678901234567890"
            )
        )

        assertEquals(null, base.pauseForApproval(approval.id, projectMismatch, nowMs = 2_000L))
        assertEquals(null, base.pauseForApproval(approval.id, secretPayload, nowMs = 2_000L))
    }

    @Test
    fun failedOrCancelledRunNeverRemainsActive() {
        val failed = sampleTask()
            .beginRun("run-fail", "collect", nowMs = 2_000L)
            .failStep("collect", "Network unavailable", nowMs = 2_100L)
            .fail("Network unavailable", nowMs = 2_200L)

        assertEquals(DurableTaskStatus.FAILED, failed.status)
        assertEquals(TaskRunStatus.FAILED, failed.runs.single().status)
        assertEquals(TaskStepStatus.FAILED, failed.plan.single().status)
        assertTrue(failed.canRetry)

        val cancelled = sampleTask()
            .beginRun("run-cancel", "collect", nowMs = 3_000L)
            .cancel(nowMs = 3_100L)

        assertEquals(DurableTaskStatus.CANCELLED, cancelled.status)
        assertEquals(TaskRunStatus.CANCELLED, cancelled.runs.single().status)
        assertFalse(cancelled.canRetry)
    }

    private fun sampleTask() = DurableTask(
        id = "task-sample",
        projectId = "project-a",
        ownerId = "user-a",
        title = "Collect sources",
        description = "Collect sources",
        agentId = "research",
        input = "Collect sources",
        plan = listOf(TaskPlanStep(id = "collect", title = "Collect sources"))
    )
}
