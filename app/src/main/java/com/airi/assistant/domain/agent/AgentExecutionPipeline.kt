package com.airi.assistant.domain.agent

import com.airi.assistant.ai.agent.AgentController
import com.airi.assistant.ai.agent.AgentResult
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.domain.monetization.SubscriptionManager
import com.airi.assistant.domain.policy.PolicyEngine
import com.airi.assistant.memory.entity.ChatMessage
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.UUID

class AgentExecutionPipeline(
    private val agentController: AgentController
) {

    companion object {
        private const val TAG                 = "AgentExecutionPipeline"
        private const val EXECUTION_TIMEOUT_MS = 15_000L
    }

    sealed class PipelineResult {
        data class Success(val agentResult: AgentResult) : PipelineResult()
        data class PolicyDenied(val error: AppError) : PipelineResult()
        data class Failed(val error: AppError) : PipelineResult()
        object NoAgentMatch : PipelineResult()
    }

    suspend fun execute(
        input: String,
        history: List<ChatMessage>,
        rateLimitKey: String = "global_agent",
        subscriptionManager: SubscriptionManager? = null
    ): PipelineResult {
        val traceId   = UUID.randomUUID().toString().take(8)
        val startTime = System.currentTimeMillis()

        // ── Step 1: Emit started event ─────────────────────────────────────────
        EventBus.emitSync(AppEvent.AgentExecutionStarted(input.take(120), traceId))
        LoggingService.debug(TAG, "[$traceId] Pipeline start: '${input.take(60)}'")

        // ── Step 2: Validate input ─────────────────────────────────────────────
        val inputCheck = PolicyEngine.checkAgentExecution(input)
        if (inputCheck is PolicyEngine.PolicyResult.Denied) {
            AppErrorHandler.log(inputCheck.error)
            EventBus.emitSync(AppEvent.AgentExecutionFailed(traceId, inputCheck.error.message))
            return PipelineResult.PolicyDenied(inputCheck.error)
        }

        // ── Step 3: Subscription check ─────────────────────────────────────────
        if (subscriptionManager != null) {
            val subCheck = PolicyEngine.checkSubscriptionAgent(subscriptionManager)
            if (subCheck is PolicyEngine.PolicyResult.Denied) {
                AppErrorHandler.log(subCheck.error)
                EventBus.emitSync(AppEvent.AgentExecutionCancelled(traceId, "Daily agent limit reached"))
                return PipelineResult.PolicyDenied(subCheck.error)
            }
        }

        // ── Step 4: Rate limit ─────────────────────────────────────────────────
        val rateCheck = PolicyEngine.checkRateLimit(rateLimitKey)
        if (rateCheck is PolicyEngine.PolicyResult.Denied) {
            AppErrorHandler.log(rateCheck.error)
            EventBus.emitSync(AppEvent.AgentExecutionCancelled(traceId, "Rate limit exceeded"))
            return PipelineResult.PolicyDenied(rateCheck.error)
        }

        // ── Step 5: Execute with timeout ───────────────────────────────────────
        return try {
            val agentResult = withTimeout(EXECUTION_TIMEOUT_MS) {
                agentController.handle(input, history)
            }

            val durationMs = System.currentTimeMillis() - startTime

            if (agentResult == null) {
                LoggingService.debug(TAG, "[$traceId] No agent match → LLM fallback (${durationMs}ms)")
                EventBus.emitSync(AppEvent.AgentExecutionCancelled(traceId, "No agent match"))
                PipelineResult.NoAgentMatch
            } else {
                LoggingService.logExecution(TAG, input, agentResult.success, durationMs)
                EventBus.emitSync(AppEvent.AgentExecutionSuccess(traceId, durationMs, agentResult.agentTag))
                if (subscriptionManager?.canExecuteAgent() == true) {
                    subscriptionManager.recordAgentExecution()
                }
                PipelineResult.Success(agentResult)
            }
        } catch (e: TimeoutCancellationException) {
            val error = AppError.AgentExecutionFailed(
                "Agent timed out after ${EXECUTION_TIMEOUT_MS / 1000}s", e
            )
            AppErrorHandler.log(error)
            EventBus.emitSync(AppEvent.AgentExecutionTimeout(traceId))
            PipelineResult.Failed(error)
        } catch (e: Exception) {
            val durationMs = System.currentTimeMillis() - startTime
            AppErrorHandler.capture(e, "AgentExecutionPipeline[$traceId]")
            val error = AppError.AgentExecutionFailed(e.message ?: "Unknown error", e)
            EventBus.emitSync(AppEvent.AgentExecutionFailed(traceId, error.message))
            PipelineResult.Failed(error)
        }
    }
}
