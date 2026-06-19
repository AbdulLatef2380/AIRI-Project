package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef
import com.airi.assistant.tools.execution.SearchTool

/**
 * ResearchAgentSkill — multi-step research: searches the web, fetches top pages,
 * synthesizes a comprehensive answer using the active model.
 */
class ResearchAgentSkill(private val context: Context) : AiriSkill {

    override val skillId    = "research_agent"
    override val name       = "research_agent"
    override val description = "Deep research assistant: searches multiple sources, reads web pages, and synthesizes a comprehensive answer"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "AI"
    override val iconEmoji  = "🔬"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.READ_WRITE
    override val modelAccess  = SkillModelAccess.CHAT

    override val parameters = mapOf(
        "query" to "string — the research question or topic",
        "depth" to "int (optional) — number of sources to read: 1–5, default 3"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "research",
            description = "Perform deep research on a topic using multiple web sources",
            parameters  = mapOf(
                "query" to SkillParamDef("string", "The research question or topic", required = true),
                "depth" to SkillParamDef("int",    "Number of sources to read (1-5, default 3)", required = false)
            )
        )
    )

    private val researchKeywords = listOf(
        "research", "investigate", "deep dive", "find out", "comprehensive",
        "analysis", "analyze", "study", "explore", "in depth", "thorough",
        "detailed report", "write a report", "summarize topic"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        if (lower.contains("research")) score += 40
        researchKeywords.forEach { kw -> if (lower.contains(kw)) score += 12 }
        if (lower.length > 50) score += 10
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start      = System.currentTimeMillis()
        val skillCtx   = params["context"] as? SkillContext
        val modelBridge = skillCtx?.modelBridge
        val apiKey     = skillCtx?.configValues?.get("brave_api_key")

        val query = (params["query"] as? String
            ?: params["input"] as? String
            ?: "").trim()

        if (query.isBlank()) {
            return SkillResult(false, "", "Research query cannot be empty.", skillId)
        }

        val depth = (params["depth"] as? String)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
        val tool  = SearchTool(context, braveApiKey = apiKey)
        val rawSources = StringBuilder()
        val toolOutputs = mutableListOf<SkillResult.ToolOutput>()

        // Step 1: Search
        val searchResult = try {
            if (!apiKey.isNullOrBlank()) {
                val brave = tool.searchBrave(query, count = depth, enrich = true)
                if (brave.success) {
                    toolOutputs.add(SkillResult.ToolOutput("web_search", brave.toAgentString()))
                    rawSources.append("=== Search Results ===\n${brave.toAgentString()}\n\n")

                    // Step 2: Fetch top pages for deeper content
                    val urlsToFetch = brave.results.take(minOf(depth, 3)).mapNotNull { it.url }
                    urlsToFetch.forEachIndexed { idx, url ->
                        val page = tool.fetchViaJina(url, maxChars = 2000)
                        if (page.success && page.content.isNotBlank()) {
                            rawSources.append("=== Source ${idx + 1}: $url ===\n${page.content}\n\n")
                            toolOutputs.add(SkillResult.ToolOutput("fetch_url", page.content))
                        }
                    }
                    "brave_search_success"
                } else {
                    null
                }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }

        if (searchResult == null) {
            val ddg = tool.searchDuckDuckGo(query)
            if (ddg.success) {
                rawSources.append("=== Search Results ===\n${ddg.summary}\n\n")
                toolOutputs.add(SkillResult.ToolOutput("web_search", ddg.summary))
            }
        }

        if (rawSources.isBlank()) {
            return SkillResult(
                false, "",
                "Could not retrieve research sources for: $query\n\nTip: Add a Brave Search API key in Settings for better results.",
                skillId
            )
        }

        // Step 3: Synthesize with model (if available)
        if (modelBridge != null) {
            val synthesisPrompt = """Based on the following research sources, provide a comprehensive, accurate answer to: "$query"

$rawSources

Write a well-structured response with:
1. A clear summary answer
2. Key findings from the sources
3. Important details and context
4. Source references where relevant"""

            val systemPrompt = "You are a research analyst. Synthesize the provided sources into a comprehensive, factual response. Be thorough but concise."

            return try {
                val synthesis = modelBridge.complete(synthesisPrompt, systemPrompt, maxTokens = 2048)
                SkillResult(
                    success     = true,
                    data        = synthesis,
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    toolOutputs = toolOutputs,
                    metadata    = mapOf("query" to query, "sources" to "${toolOutputs.size}", "depth" to "$depth")
                )
            } catch (e: Exception) {
                SkillResult(
                    success     = true,
                    data        = "Research results for: $query\n\n${rawSources.take(4000)}",
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    toolOutputs = toolOutputs
                )
            }
        }

        return SkillResult(
            success     = true,
            data        = "Research results for: $query\n\n${rawSources.take(4000)}\n\n[Note: Load an AI model to get a synthesized summary]",
            skillName   = skillId,
            executionMs = System.currentTimeMillis() - start,
            toolOutputs = toolOutputs
        )
    }
}
