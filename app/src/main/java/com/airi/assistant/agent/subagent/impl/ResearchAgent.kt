package com.airi.assistant.agent.subagent.impl

import android.util.Log
import com.airi.assistant.agent.subagent.AgentEvent
import com.airi.assistant.agent.subagent.SubAgent
import com.airi.assistant.agent.subagent.SubAgentCapability
import com.airi.assistant.agent.subagent.SubAgentContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * ResearchAgent — web search, fact-finding, summarization, and deep research.
 *
 * Designed for multi-turn research tasks. Emits Progress events throughout
 * so the UI shows meaningful step-by-step activity feedback.
 *
 * Supports background execution for long-form research reports.
 */
class ResearchAgent : SubAgent {

    override val capability = SubAgentCapability(
        agentId      = "research_agent",
        displayName  = "Research Agent",
        description  = "Search the web, summarize information, and conduct deep research on any topic.",
        intentKeywords = listOf(
            "search", "find", "look up", "research", "what is", "who is",
            "when did", "where is", "how does", "news", "latest", "current",
            "summarize", "explain", "tell me about", "facts about",
            "compare", "difference between", "vs", "best", "top"
        ),
        domains        = listOf("research", "information", "news", "web search", "facts"),
        requiresCloud  = true,
        requiredTools  = listOf("web_search_tool"),
        costTier       = SubAgentCapability.CostTier.MEDIUM,
        latencyProfile = SubAgentCapability.LatencyProfile.MODERATE,
        supportsBackground  = true,
        maxParallelSubTasks = 3,
        supportsResume      = true
    )

    override suspend fun canHandle(input: String, context: SubAgentContext): Boolean {
        if (!context.cloudAllowed) return false
        val lower = input.lowercase()
        val researchSignals = listOf(
            "search for", "find out", "look up", "research", "what is",
            "who is", "tell me about", "news about", "latest on",
            "facts about", "compare", "summarize"
        )
        return researchSignals.any { lower.contains(it) }
    }

    override fun execute(input: String, context: SubAgentContext): Flow<AgentEvent> = flow {
        val start = System.currentTimeMillis()
        Log.i(TAG, "ResearchAgent.execute input='${input.take(80)}'")

        emit(AgentEvent.Progress("Understanding your research request…", 5, "parse"))

        val researchType = detectResearchType(input.lowercase())
        emit(AgentEvent.Progress("Research type: $researchType", 15, "classify"))

        emit(AgentEvent.ToolCall(
            toolName  = "web_search_tool",
            params    = mapOf("query" to extractSearchQuery(input), "maxResults" to "5"),
            reasoning = "Searching the web for: $researchType"
        ))

        emit(AgentEvent.Progress("Searching the web…", 35, "search"))

        if (researchType == "deep_research" && context.maxParallelSubTasks > 1) {
            emit(AgentEvent.Progress("Running parallel research threads…", 50, "parallel_search"))
        }

        emit(AgentEvent.Progress("Synthesizing results…", 75, "synthesis"))

        val synthesisPrompt = buildResearchPrompt(input, researchType, context)
        emit(AgentEvent.Delegate(
            targetAgentId = "llm_backend",
            subInput      = synthesisPrompt,
            reason        = "Research synthesis requires LLM"
        ))

        val durationMs = System.currentTimeMillis() - start
        emit(AgentEvent.Complete(
            result     = "[ResearchAgent delegated synthesis to LLM]",
            durationMs = durationMs,
            toolsUsed  = listOf("web_search_tool")
        ))
    }

    private fun detectResearchType(lower: String): String = when {
        lower.contains("latest") || lower.contains("news") || lower.contains("current") -> "current_events"
        lower.contains("compare") || lower.contains("vs")  || lower.contains("difference") -> "comparison"
        lower.contains("summarize") || lower.contains("overview") -> "summarization"
        lower.contains("deep") || lower.contains("comprehensive") || lower.contains("detailed") -> "deep_research"
        else -> "factual_lookup"
    }

    private fun extractSearchQuery(input: String): String {
        val stopWords = setOf("search for", "find out", "look up", "tell me about", "what is", "who is")
        var query = input.lowercase()
        stopWords.forEach { sw -> query = query.replace(sw, "").trim() }
        return query.take(150)
    }

    private fun buildResearchPrompt(input: String, type: String, context: SubAgentContext): String {
        val recency = if (type == "current_events") " Focus on the most recent information." else ""
        val recentTurns = if (context.recentTurns.isNotEmpty()) {
            "\nConversation context:\n" + context.recentTurns.takeLast(3).joinToString("\n")
        } else ""
        return """You are AIRI's research specialist. The user asked: "$input"

Research type: $type$recency
Provide accurate, well-structured information. Note source credibility where relevant.
If uncertain, explicitly state uncertainty — never fabricate facts.$recentTurns"""
    }

    companion object {
        private const val TAG = "ResearchAgent"
    }
}
