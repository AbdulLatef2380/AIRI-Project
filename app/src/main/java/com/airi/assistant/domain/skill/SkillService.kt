package com.airi.assistant.domain.skill

import android.content.Context
import com.airi.assistant.agent.learning.SkillOutcomeScorer
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.intent.ToolCallParser
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillExecutor
import com.airi.assistant.ai.skills.SkillRegistry
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.ai.tools.ToolResult
import com.airi.assistant.analytics.AnalyticsService
import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor
import com.airi.assistant.domain.customskill.CustomSkillRepository
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.monetization.ActionType
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.policy.PolicyDecision
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.domain.policy.UnifiedPolicyGate

class SkillService(private val context: Context) {

    companion object {
        private const val TAG = "SkillService"

        internal fun resultForToolExecution(
            toolCall: ToolCall,
            result: ToolResult
        ): ToolCallResult = if (result.success) {
            ToolCallResult.Executed(toolCall, result)
        } else {
            ToolCallResult.Failed(
                toolName = toolCall.toolName,
                errorMessage = result.error ?: "Tool execution failed."
            )
        }
    }

    private val skillExecutor         = SkillExecutor(context)
    private val skillRegistry         = SkillRegistry(context)
    private val toolExecutor          = ToolExecutor(context)
    private val customSkillExecutor   = CustomSkillExecutor(context)
    private val customSkillRepository = CustomSkillRepository(context)
    private val outcomeScorer         = SkillOutcomeScorer.getInstance(context)

    // ── Policy-gated skill execution ──────────────────────────────────────────

    suspend fun tryHandle(
        input:               String,
        skillContext:        SkillContext,
        subscriptionManager: SubscriptionManager? = null
    ): SkillResult? {
        // ── Legacy policy: input validation ───────────────────────────────────
        val check = PolicyEngine.checkAgentExecution(input)
        if (check is PolicyEngine.PolicyResult.Denied) {
            AppErrorHandler.log(check.error)
            return null
        }

        // ── Legacy policy: skill usage quota ──────────────────────────────────
        if (subscriptionManager != null) {
            val subCheck = PolicyEngine.checkSubscriptionSkill(subscriptionManager)
            if (subCheck is PolicyEngine.PolicyResult.Denied) {
                AppErrorHandler.log(subCheck.error)
                return null
            }
        }

        // ── Unified policy gate (credit + permission + outcome scorer) ─────────
        val creditEngine      = runCatching { com.airi.assistant.core.ServiceLocator.creditMeteringEngine }.getOrNull()
        val permissionService = runCatching { com.airi.assistant.core.ServiceLocator.permissionService }.getOrNull()
        if (creditEngine != null && permissionService != null) {
            val gate = UnifiedPolicyGate.check(
                creditEngine        = creditEngine,
                permissionService   = permissionService,
                outcomeScorer       = outcomeScorer,
                toolName            = "skill:$input",
                action              = ActionType.SKILL_USE
            )
            if (gate is PolicyDecision.Deny) {
                LoggingService.warn(TAG, "UnifiedPolicyGate denied skill execution: ${gate.userMessage}")
                return null
            }
        }

        val startTime  = System.currentTimeMillis()
        val skillLabel = "SkillExecution"
        EventBus.emitSync(AppEvent.SkillExecutionStarted(skillLabel, input.take(80)))

        return try {
            val result    = skillExecutor.tryHandle(input, skillContext)
            val latencyMs = System.currentTimeMillis() - startTime

            // ── Self-improvement: record outcome ──────────────────────────────
            outcomeScorer.record(
                skillName = skillLabel,
                success   = result != null,
                latencyMs = latencyMs
            )

            EventBus.emitSync(AppEvent.SkillExecutionCompleted(skillLabel, result != null, latencyMs))
            if (result != null) subscriptionManager?.recordSkillUse()
            result
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startTime
            val error     = AppError.SkillExecutionFailed("unknown", e.message ?: "Unknown error", e)
            AppErrorHandler.log(error)

            // ── Self-improvement: record failure ──────────────────────────────
            outcomeScorer.record(
                skillName   = skillLabel,
                success     = false,
                latencyMs   = latencyMs,
                errorReason = e.message
            )

            EventBus.emitSync(AppEvent.SkillExecutionCompleted(skillLabel, false, latencyMs))
            null
        }
    }

    // ── Policy-gated tool call execution ──────────────────────────────────────

    suspend fun executeToolCall(response: String): ToolCallResult {
        val toolCall = ToolCallParser.parse(response) ?: return ToolCallResult.NoToolCall

        LoggingService.debug(TAG, "Tool call detected: ${toolCall.toolName}")

        // ── Unified policy gate ────────────────────────────────────────────────
        val creditEngine      = runCatching { com.airi.assistant.core.ServiceLocator.creditMeteringEngine }.getOrNull()
        val permissionService = runCatching { com.airi.assistant.core.ServiceLocator.permissionService }.getOrNull()
        if (creditEngine != null && permissionService != null) {
            val gate = UnifiedPolicyGate.check(
                creditEngine      = creditEngine,
                permissionService = permissionService,
                outcomeScorer     = outcomeScorer,
                toolName          = toolCall.toolName,
                action            = ActionType.SKILL_USE
            )
            if (gate is PolicyDecision.Deny) {
                LoggingService.warn(TAG, "UnifiedPolicyGate denied tool ${toolCall.toolName}: ${gate.userMessage}")
                outcomeScorer.record(toolCall.toolName, success = false, errorReason = "policy_denied")
                return ToolCallResult.Failed(toolCall.toolName, gate.userMessage)
            }
        }

        EventBus.emitSync(AppEvent.SkillExecutionStarted("Tool:${toolCall.toolName}", toolCall.toolName))
        val startMs = System.currentTimeMillis()

        return try {
            val result    = toolExecutor.execute(toolCall)
            val latencyMs = System.currentTimeMillis() - startMs
            outcomeScorer.record(
                skillName = toolCall.toolName,
                success = result.success,
                latencyMs = latencyMs,
                errorReason = if (result.success) null else "tool_execution_failed"
            )
            EventBus.emitSync(AppEvent.ToolCallExecuted(toolCall.toolName, result.success))
            resultForToolExecution(toolCall, result)
        } catch (e: Exception) {
            val latencyMs = System.currentTimeMillis() - startMs
            val error     = AppError.SkillExecutionFailed(toolCall.toolName, e.message ?: "Unknown", e)
            AppErrorHandler.log(error)
            outcomeScorer.record(toolCall.toolName, success = false, latencyMs = latencyMs, errorReason = e.message)
            EventBus.emitSync(AppEvent.ToolCallExecuted(toolCall.toolName, false))
            ToolCallResult.Failed(toolCall.toolName, AppErrorHandler.toUserMessage(error))
        }
    }

    // ── Premium custom skill execution ────────────────────────────────────────

    suspend fun executeCustomSkill(
        skill: CustomSkill,
        input: Map<String, Any>
    ): SkillResult {
        val subscriptionManager = runCatching { com.airi.assistant.core.ServiceLocator.subscriptionManager }.getOrNull()
        if (skill.isPremium && subscriptionManager != null) {
            val premiumCheck = PolicyEngine.checkCustomSkillsPremium(subscriptionManager)
            if (premiumCheck is PolicyEngine.PolicyResult.Denied) {
                AppErrorHandler.log(premiumCheck.error)
                AnalyticsService.skillFailed(skill.name, "premium_required")
                return SkillResult(false, "", AppErrorHandler.toUserMessage(premiumCheck.error), skill.name)
            }
            val quotaCheck = PolicyEngine.checkSubscriptionSkill(subscriptionManager)
            if (quotaCheck is PolicyEngine.PolicyResult.Denied) {
                AppErrorHandler.log(quotaCheck.error)
                AnalyticsService.skillFailed(skill.name, "quota_denied")
                return SkillResult(false, "", AppErrorHandler.toUserMessage(quotaCheck.error), skill.name)
            }
        }

        // ── Unified policy gate for custom skills ─────────────────────────────
        val creditEngine      = runCatching { com.airi.assistant.core.ServiceLocator.creditMeteringEngine }.getOrNull()
        val permissionService = runCatching { com.airi.assistant.core.ServiceLocator.permissionService }.getOrNull()
        if (creditEngine != null && permissionService != null) {
            val gate = UnifiedPolicyGate.check(
                creditEngine      = creditEngine,
                permissionService = permissionService,
                outcomeScorer     = outcomeScorer,
                toolName          = "custom:${skill.name}",
                action            = ActionType.SKILL_USE
            )
            if (gate is PolicyDecision.Deny) {
                outcomeScorer.record("custom:${skill.name}", false, errorReason = "policy_denied")
                return SkillResult(false, "", gate.userMessage, skill.name)
            }
        }

        val startMs = System.currentTimeMillis()
        val result  = customSkillExecutor.execute(skill, input)
        val latency = System.currentTimeMillis() - startMs
        outcomeScorer.record("custom:${skill.name}", result.success, latency,
            if (!result.success) result.error else null)
        if (result.success) subscriptionManager?.recordSkillUse()
        return result
    }

    // ── Self-improvement query API ────────────────────────────────────────────

    /** Get improvement suggestion for a skill (feed to planner for self-correction). */
    fun getImprovementSuggestion(skillName: String): String? =
        outcomeScorer.improvementSuggestion(skillName)

    /** Ranked skill report for the observability UI. */
    fun getSkillRankings(): List<SkillOutcomeScorer.SkillReport> =
        outcomeScorer.rankedSkills()

    // ── Registry access ───────────────────────────────────────────────────────

    fun getAllSkillInfos(): List<SkillRegistry.SkillInfo> = skillRegistry.getAllSkillInfos()
    fun getCustomSkills(): List<CustomSkill>              = customSkillRepository.getAllSkills()
    fun getToolList(): List<Pair<String, String>>         = toolExecutor.getToolList()
    fun setSkillEnabled(name: String, enabled: Boolean)   = skillRegistry.setSkillEnabled(name, enabled)

    sealed class ToolCallResult {
        object NoToolCall : ToolCallResult()
        data class Executed(val toolCall: ToolCall, val result: ToolResult) : ToolCallResult()
        data class Failed(val toolName: String, val errorMessage: String) : ToolCallResult()
    }
}
