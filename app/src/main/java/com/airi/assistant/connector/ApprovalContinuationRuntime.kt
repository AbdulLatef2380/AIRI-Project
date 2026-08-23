package com.airi.assistant.connector

import com.airi.assistant.agent.durable.DurableTaskManager
import com.airi.assistant.ui.activity.ActivityCategory
import com.airi.assistant.ui.activity.ActivitySeverity
import com.airi.assistant.ui.activity.AgentActivityBus

/**
 * Executes a previously approved connector continuation on a fresh coroutine
 * scope. The durable manager claims the continuation before this class touches
 * a connector, making approval delivery idempotent across duplicate UI actions,
 * stale callbacks, and process recreation.
 */
class ApprovalContinuationRuntime(
    private val durableTaskManager: DurableTaskManager,
    private val connectorRuntimeManager: ConnectorRuntimeManager
) {
    /** Restores only previously approved continuations after a process restart. */
    suspend fun resumeApprovedAfterRecovery(): List<ConnectorOutput> =
        durableTaskManager.approvedConnectorContinuationApprovalIds().mapNotNull { approvalId ->
            resume(approvalId)
        }

    suspend fun resume(approvalId: String): ConnectorOutput? {
        val pending = durableTaskManager.continuationForApproval(approvalId) ?: return null
        if (pending.invocation == null || pending.projectFileWrite != null) return null
        val continuation = durableTaskManager.claimApprovedContinuation(approvalId)
            ?: return null
        val invocation = continuation.invocation ?: return null
        val execution = ConnectorExecutionContext(
            projectId = continuation.projectId,
            taskId = continuation.taskId,
            missionId = continuation.missionId,
            runId = continuation.runId,
            stepId = continuation.stepId,
            idempotencyKey = invocation.idempotencyKey,
            continuationId = continuation.id
        )
        val input = ConnectorInput(
            action = invocation.action,
            text = invocation.text,
            params = invocation.params,
            execution = execution
        )
        val output = connectorRuntimeManager.execute(
            connectorId = invocation.connectorId,
            input = input,
            // A claimed side effect may not be transport-retried automatically:
            // an ambiguous timeout or network error requires an explicit recovery
            // decision, not a second potentially duplicate mutation.
            maxRetries = 0
        )
        val succeeded = output is ConnectorOutput.Success || output is ConnectorOutput.Streaming
        val outcome = when (output) {
            is ConnectorOutput.Success -> output.text
            is ConnectorOutput.Streaming -> "Streaming connector response started"
            is ConnectorOutput.Failure -> "${output.code}: ${output.message}"
            is ConnectorOutput.ApprovalRequired -> "Unexpected nested approval request"
        }
        durableTaskManager.finishApprovalContinuation(
            continuationId = continuation.id,
            outcome = outcome,
            succeeded = succeeded
        )
        if (succeeded) {
            durableTaskManager.markStepCompleted(continuation.taskId, continuation.stepId)
            AgentActivityBus.emit(
                "Approved ${invocation.connectorId} action completed",
                ActivityCategory.CONNECTOR
            )
        } else {
            durableTaskManager.markStepFailed(continuation.taskId, continuation.stepId, outcome)
            AgentActivityBus.emit(
                "Approved ${invocation.connectorId} action did not complete",
                ActivityCategory.CONNECTOR,
                ActivitySeverity.WARN
            )
        }
        return output
    }
}
