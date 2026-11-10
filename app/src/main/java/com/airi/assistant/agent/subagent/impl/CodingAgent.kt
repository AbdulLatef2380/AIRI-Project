package com.airi.assistant.agent.subagent.impl

import android.util.Log
import com.airi.assistant.agent.coding.CodingAgentRuntime
import com.airi.assistant.agent.coding.CodingAgentStatus
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * CodingAgent — code generation, explanation, debugging, and review.
 *
 * Routes cloud LLM for generation/review; local model for quick explanations.
 * Emits [AgentEvent.PartialResult] chunks for streaming display.
 */
class CodingAgent(
    private val runtimeProvider: () -> CodingAgentRuntime,
) : SubAgent {

    override val capability = SubAgentCapability(
        agentId      = "coding_agent",
        displayName  = "Coding Agent",
        description  = "Write, explain, debug, and review code in any language.",
        intentKeywords = listOf(
            "code", "function", "class", "bug", "debug", "error",
            "write", "implement", "refactor", "explain this", "fix",
            "algorithm", "script", "kotlin", "python", "javascript",
            "java", "swift", "compile", "syntax", "test", "unit test"
        ),
        domains        = listOf("programming", "software", "debugging", "code review"),
        requiresCloud  = true,
        costTier       = SubAgentCapability.CostTier.HIGH,
        latencyProfile = SubAgentCapability.LatencyProfile.MODERATE,
        supportsBackground = false,
        maxParallelSubTasks = 1,
        supportsResume = false
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        val lower = input.lowercase()
        val codeSignals = listOf(
            "write a", "create a function", "implement", "debug",
            "fix this", "explain this code", "refactor", "code for",
            "```", "error in", "compile error", "null pointer",
            "how do i", "how to", "what does this do"
        )
        return codeSignals.any { lower.contains(it) } ||
               lower.contains("code") ||
               lower.contains("function") ||
               lower.contains("class")
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "CODING_AGENT_EXECUTE inputChars=${input.length}")

        emit(AgentEvent.Progress("Preparing a bounded coding workspace…", stepName = "admission"))
        val result = runtimeProvider().execute(input, context)
        emit(AgentEvent.PartialResult(result.evidence, isFinal = false))
        if (result.report.status == com.airi.assistant.agent.coding.CodingRepairStatus.SUCCEEDED) {
            emit(AgentEvent.Complete(
                result = result.evidence,
                durationMs = System.currentTimeMillis() - start,
                toolsUsed = result.report.finalExecution.steps.map { it.toolId },
            ))
        } else {
            emit(AgentEvent.Failed(
                reason = result.report.terminalReason ?: result.report.status.name.lowercase(),
                recoverable = result.report.status == com.airi.assistant.agent.coding.CodingRepairStatus.EXHAUSTED,
                errorCode = "coding_${result.report.status.name.lowercase()}",
            ))
        }
    }

    companion object { private const val TAG = "CodingAgent" }
}
