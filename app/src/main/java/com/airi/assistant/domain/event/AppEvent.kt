package com.airi.assistant.domain.event

import java.util.UUID

sealed class AppEvent(
    val timestamp: Long = System.currentTimeMillis(),
    val eventId: String = UUID.randomUUID().toString()
) {
    // ── Agent Events ──────────────────────────────────────────────────────────

    class AgentExecutionStarted(val input: String, val traceId: String) : AppEvent()
    class AgentExecutionSuccess(
        val traceId: String,
        val durationMs: Long,
        val agentTag: String? = null
    ) : AppEvent()
    class AgentExecutionFailed(val traceId: String, val error: String) : AppEvent()
    class AgentExecutionTimeout(val traceId: String) : AppEvent()
    class AgentExecutionCancelled(val traceId: String, val reason: String) : AppEvent()

    // ── Policy Events ─────────────────────────────────────────────────────────

    class PolicyChecked(
        val rule: String,
        val passed: Boolean,
        val reason: String? = null
    ) : AppEvent()

    // ── Skill / Tool Events ───────────────────────────────────────────────────

    class SkillExecutionStarted(val skillName: String, val input: String) : AppEvent()
    class SkillExecutionCompleted(
        val skillName: String,
        val success: Boolean,
        val durationMs: Long
    ) : AppEvent()
    class ToolCallExecuted(val toolName: String, val success: Boolean) : AppEvent()

    // ── Auth Events ───────────────────────────────────────────────────────────

    class UserSignedIn(val userId: String, val method: String) : AppEvent()
    class UserSignedOut : AppEvent()
    class AuthFailed(val reason: String) : AppEvent()

    // ── Monetization Events ───────────────────────────────────────────────────

    class SubscriptionChecked(
        val tier: String,
        val featureAllowed: Boolean,
        val feature: String
    ) : AppEvent()
    class UsageLimitReached(val limitType: String, val current: Int, val max: Int) : AppEvent()
    class PremiumRequired(val feature: String) : AppEvent()

    // ── Permission Events ─────────────────────────────────────────────────────

    class PermissionGranted(val permission: String) : AppEvent()
    class PermissionDenied(val permission: String, val permanent: Boolean) : AppEvent()

    // ── General / Infrastructure Events ───────────────────────────────────────

    /** Generic informational event for services that don't have a dedicated type. */
    class GenericInfo(val message: String) : AppEvent()

    // ── Scheduled Job Events ───────────────────────────────────────────────────

    class ScheduledJobQueued(val jobId: String, val agentId: String, val label: String) : AppEvent()
    class ScheduledJobFired(val jobId: String, val agentId: String) : AppEvent()

    // ── Chat Sharing Events ────────────────────────────────────────────────────

    class ChatSharePublished(val shareId: String, val shareUrl: String) : AppEvent()
    class ChatShareDeleted(val shareId: String) : AppEvent()

    // ── Model Governance Events ────────────────────────────────────────────────

    class ModelGovernanceDecision(
        val strategy:  String,
        val rationale: String
    ) : AppEvent()

    // ── RAG / Retrieval Events ─────────────────────────────────────────────────

    class RagContextBuilt(val sessionId: String, val hitsCount: Int, val chars: Int) : AppEvent()

    // ── Credit Metering Events ─────────────────────────────────────────────────

    class CreditConsumed(
        val action:     String,
        val weight:     Int,
        val dailyTotal: Int,
        val budget:     Int
    ) : AppEvent()
}
