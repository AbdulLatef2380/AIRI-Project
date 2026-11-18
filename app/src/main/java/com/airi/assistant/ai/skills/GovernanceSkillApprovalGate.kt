package com.airi.assistant.ai.skills

import com.airi.assistant.agent.durable.ApprovalGrantScope
import com.airi.assistant.security.PermissionGovernanceLayer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/** Immutable, user-facing request bound to the exact invocation being paused. */
data class SkillApprovalRequest(
    val skillId: String,
    val toolName: String,
    val description: String,
    val riskLevel: PermissionGovernanceLayer.RiskLevel,
    val sessionId: String? = null,
    val taskId: String? = null,
    val runId: String? = null,
    val stepId: String? = null,
)

/** User decision gate. A model tool-call is never a decision. */
interface SkillApprovalGate {
    suspend fun awaitApproval(request: SkillApprovalRequest): Boolean
    fun approveFromTrustedUi(approvalId: String): Boolean
    fun denyFromTrustedUi(approvalId: String): Boolean
}

/**
 * Bridges PermissionGovernanceLayer's observable pending list to the suspended
 * tool invocation. Approvals are one-shot and bound to this exact action.
 */
class GovernanceSkillApprovalGate(
    private val governance: PermissionGovernanceLayer,
    private val timeoutMs: Long = 60_000L,
) : SkillApprovalGate {
    private data class Waiting(
        val action: String,
        val completion: CompletableDeferred<Boolean>,
    )

    private val waiting = ConcurrentHashMap<String, Waiting>()

    override suspend fun awaitApproval(request: SkillApprovalRequest): Boolean {
        val action = "skill:${request.skillId}:${request.toolName}"
        val approvalId = governance.requestApproval(
            action = action,
            description = request.description,
            riskLevel = request.riskLevel,
            sourceSessionId = request.sessionId,
            taskId = request.taskId,
            runId = request.runId,
            stepId = request.stepId,
            expiresInMs = timeoutMs,
        )
        val waiter = Waiting(action, CompletableDeferred())
        waiting[approvalId] = waiter
        return try {
            withTimeoutOrNull(timeoutMs) { waiter.completion.await() } == true
        } finally {
            waiting.remove(approvalId, waiter)
            if (governance.pendingApprovals.value.any { it.id == approvalId }) {
                governance.denyAction(approvalId, "Approval expired or invocation cancelled")
            }
        }
    }

    override fun approveFromTrustedUi(approvalId: String): Boolean = resolve(approvalId, true)
    override fun denyFromTrustedUi(approvalId: String): Boolean = resolve(approvalId, false)

    private fun resolve(approvalId: String, approved: Boolean): Boolean {
        val waiter = waiting[approvalId] ?: return false
        val pending = governance.pendingApprovals.value.firstOrNull { it.id == approvalId } ?: return false
        if (pending.action != waiter.action || !pending.action.startsWith("skill:")) return false

        val recorded = if (approved) {
            governance.approveAction(approvalId, ApprovalGrantScope.ONCE)
        } else {
            governance.denyAction(approvalId, "Denied by user")
        }
        if (!recorded) return false
        return waiter.completion.complete(approved)
    }
}
