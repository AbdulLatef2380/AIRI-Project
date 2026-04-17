package com.airi.assistant.domain.skill

import android.content.Context
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
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.policy.PolicyEngine

class SkillService(private val context: Context) {

    private companion object {
        private const val TAG = "SkillService"
    }

    private val skillExecutor = SkillExecutor(context)
    private val skillRegistry = SkillRegistry(context)
    private val toolExecutor  = ToolExecutor(context)
    private val customSkillExecutor = CustomSkillExecutor(context)
    private val customSkillRepository = CustomSkillRepository(context)

    suspend fun tryHandle(
        input: String,
        skillContext: SkillContext,
        subscriptionManager: SubscriptionManager? = null
    ): SkillResult? {
        // ── Policy: input validation ──────────────────────────────────────────
        val check = PolicyEngine.checkAgentExecution(input)
        if (check is PolicyEngine.PolicyResult.Denied) {
            AppErrorHandler.log(check.error)
            return null
        }

        // ── Policy: skill usage quota ─────────────────────────────────────────
        if (subscriptionManager != null) {
            val subCheck = PolicyEngine.checkSubscriptionSkill(subscriptionManager)
            if (subCheck is PolicyEngine.PolicyResult.Denied) {
                AppErrorHandler.log(subCheck.error)
                return null
            }
        }

        val startTime  = System.currentTimeMillis()
        val skillLabel = "SkillExecution"
        EventBus.emitSync(AppEvent.SkillExecutionStarted(skillLabel, input.take(80)))

        return try {
            val result = skillExecutor.tryHandle(input, skillContext)
            val durationMs = System.currentTimeMillis() - startTime
            EventBus.emitSync(AppEvent.SkillExecutionCompleted(skillLabel, result != null, durationMs))
            if (result != null) subscriptionManager?.recordSkillUse()
            result
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            val error      = AppError.SkillExecutionFailed("unknown", e.message ?: "Unknown error", e)
            AppErrorHandler.log(error)
            EventBus.emitSync(AppEvent.SkillExecutionCompleted(skillLabel, false, durationMs))
            null
        }
    }

    suspend fun executeToolCall(response: String): ToolCallResult {
        val toolCall = ToolCallParser.parse(response) ?: return ToolCallResult.NoToolCall

        LoggingService.debug(TAG, "Tool call detected: ${toolCall.toolName}")
        EventBus.emitSync(AppEvent.SkillExecutionStarted("Tool:${toolCall.toolName}", toolCall.toolName))

        return try {
            val result = toolExecutor.execute(toolCall)
            EventBus.emitSync(AppEvent.ToolCallExecuted(toolCall.toolName, true))
            ToolCallResult.Executed(toolCall, result)
        } catch (e: Exception) {
            val error = AppError.SkillExecutionFailed(toolCall.toolName, e.message ?: "Unknown", e)
            AppErrorHandler.log(error)
            EventBus.emitSync(AppEvent.ToolCallExecuted(toolCall.toolName, false))
            ToolCallResult.Failed(toolCall.toolName, AppErrorHandler.toUserMessage(error))
        }
    }

    fun getAllSkillInfos(): List<SkillRegistry.SkillInfo> = skillRegistry.getAllSkillInfos()

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
        val result = customSkillExecutor.execute(skill, input)
        if (result.success) subscriptionManager?.recordSkillUse()
        return result
    }

    fun getCustomSkills(): List<CustomSkill> = customSkillRepository.getAllSkills()

    fun getToolList(): List<Pair<String, String>> = toolExecutor.getToolList()

    fun setSkillEnabled(name: String, enabled: Boolean) {
        skillRegistry.setSkillEnabled(name, enabled)
    }

    sealed class ToolCallResult {
        object NoToolCall : ToolCallResult()
        data class Executed(val toolCall: ToolCall, val result: ToolResult) : ToolCallResult()
        data class Failed(val toolName: String, val errorMessage: String) : ToolCallResult()
    }
}
