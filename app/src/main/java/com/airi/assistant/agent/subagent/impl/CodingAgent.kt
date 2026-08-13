package com.airi.assistant.agent.subagent.impl

import android.util.Log
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
class CodingAgent : SubAgent {

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

        emit(AgentEvent.Progress("Analyzing your coding request…", stepName = "analysis"))

        val taskType = detectTaskType(input.lowercase())
        emit(AgentEvent.Progress("Task identified: $taskType", stepName = "routing"))

        // Build a structured coding prompt
        val systemContext = buildCodeSystemPrompt(taskType, context)
        val fullPrompt    = "$systemContext\n\nUser request: $input"

        emit(AgentEvent.Progress("Generating $taskType response…", stepName = "generation"))

        // The actual LLM call happens through HybridOrchestrator at the ViewModel layer.
        // CodingAgent signals intent via a structured partial result that the orchestrator
        // surfaces as a streaming response.
        // In production: the orchestrator intercepts AgentEvent.Delegate and routes to LLM.
        emit(AgentEvent.Delegate(
            targetAgentId = "llm_backend",
            subInput      = fullPrompt,
            reason        = "Coding task requires LLM: $taskType"
        ))

        val durationMs = System.currentTimeMillis() - start
        emit(AgentEvent.Complete(
            result     = "[CodingAgent delegated to LLM — streaming response]",
            durationMs = durationMs
        ))
    }

    private fun detectTaskType(lower: String): String = when {
        lower.contains("debug") || lower.contains("fix") || lower.contains("error") -> "debugging"
        lower.contains("explain") || lower.contains("what does") -> "explanation"
        lower.contains("refactor") || lower.contains("improve") -> "code review"
        lower.contains("test") || lower.contains("unit test") -> "test generation"
        lower.contains("write") || lower.contains("implement") || lower.contains("create") -> "code generation"
        else -> "code assistance"
    }

    private fun buildCodeSystemPrompt(taskType: String, context: SubAgentContext): String {
        val recentContext = if (context.recentTurns.isNotEmpty()) {
            "\n\nRecent conversation context:\n" + context.recentTurns.takeLast(4).joinToString("\n")
        } else ""
        return """You are AIRI's expert coding assistant. Task type: $taskType.
Provide clear, working, well-commented code. Explain your reasoning concisely.
If debugging, identify the root cause first before providing a fix.
If generating code, ask clarifying questions if the requirements are ambiguous.$recentContext"""
    }

    companion object { private const val TAG = "CodingAgent" }
}
