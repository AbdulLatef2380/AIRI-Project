package com.airi.assistant.domain.agent

import android.content.Context
import com.airi.assistant.ai.agent.AgentController
import com.airi.assistant.ai.agent.AgentResult
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.logging.LoggingService
import com.airi.assistant.memory.entity.ChatMessage

class AgentService(context: Context) {

    private companion object {
        private const val TAG = "AgentService"
    }

    private val agentController     = AgentController(context)
    private val pipeline            = AgentExecutionPipeline(agentController)

    data class AgentServiceResult(
        val agentResult: AgentResult?,
        val errorMessage: String?,
        val isLlmFallback: Boolean
    )

    suspend fun handle(
        input: String,
        history: List<ChatMessage>
    ): AgentServiceResult {
        LoggingService.debug(TAG, "Handling: '${input.take(80)}'")

        // Resolve subscription manager lazily so it's only accessed after full init
        val subscriptionManager = runCatching { ServiceLocator.subscriptionManager }.getOrNull()

        return when (val pipelineResult = pipeline.execute(input, history, subscriptionManager = subscriptionManager)) {
            is AgentExecutionPipeline.PipelineResult.Success ->
                AgentServiceResult(
                    agentResult    = pipelineResult.agentResult,
                    errorMessage   = null,
                    isLlmFallback  = false
                )

            is AgentExecutionPipeline.PipelineResult.NoAgentMatch ->
                AgentServiceResult(
                    agentResult    = null,
                    errorMessage   = null,
                    isLlmFallback  = true
                )

            is AgentExecutionPipeline.PipelineResult.PolicyDenied ->
                AgentServiceResult(
                    agentResult    = null,
                    errorMessage   = AppErrorHandler.toUserMessage(pipelineResult.error),
                    isLlmFallback  = false
                )

            is AgentExecutionPipeline.PipelineResult.Failed ->
                AgentServiceResult(
                    agentResult    = null,
                    errorMessage   = AppErrorHandler.toUserMessage(pipelineResult.error),
                    isLlmFallback  = false
                )
        }
    }
}
