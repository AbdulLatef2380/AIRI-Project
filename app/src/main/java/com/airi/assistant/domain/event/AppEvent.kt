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
}
