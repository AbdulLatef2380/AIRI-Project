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

class WebSearchSkill(private val context: Context) : AiriSkill {

    override val skillId    = "web_search"
    override val name       = "web_search"
    override val description = "Search the web for current information, news, facts, and answers"
    override val version    = "1.1.0"
    override val author     = "AIRI Official"
    override val category   = "SEARCH"
    override val iconEmoji  = "🔍"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.READ_WRITE
    override val modelAccess  = SkillModelAccess.NONE

    override val parameters = mapOf(
        "query"  to "string — the search query",
        "count"  to "int (optional) — number of results, default 5"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "web_search",
            description = "Search the web and return top results with summaries",
            parameters  = mapOf(
                "query" to SkillParamDef("string", "The search query", required = true),
                "count" to SkillParamDef("int",    "Number of results (default 5)", required = false)
            )
        )
    )

    private val searchKeywords = listOf(
        "search", "find", "look up", "google", "web", "internet",
        "what is", "who is", "how to", "news", "latest", "current",
        "today", "recent", "information about", "tell me about"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        searchKeywords.forEach { kw -> if (lower.contains(kw)) score += 15 }
        if (lower.startsWith("search") || lower.startsWith("find")) score += 20
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start = System.currentTimeMillis()
        val query = (params["input"] as? String
            ?: params["query"] as? String
            ?: "").trim()

        if (query.isBlank()) {
            return SkillResult(false, "", "Search query cannot be empty.", skillId)
        }

        val count = (params["count"] as? String)?.toIntOrNull() ?: 5
        val apiKey = (params["brave_api_key"] as? String)
            ?: (params["context"] as? SkillContext)?.configValues?.get("brave_api_key")

        val tool = SearchTool(context, braveApiKey = apiKey)

        return try {
            if (!apiKey.isNullOrBlank()) {
                val brave = tool.searchBrave(query, count = count, enrich = true)
                if (brave.success) {
                    return SkillResult(
                        success     = true,
                        data        = brave.toAgentString(),
                        skillName   = skillId,
                        executionMs = System.currentTimeMillis() - start,
                        metadata    = mapOf("source" to "brave", "result_count" to "${brave.results.size}")
                    )
                }
            }

            val ddg = tool.searchDuckDuckGo(query)
            if (ddg.success && ddg.summary.isNotBlank()) {
                SkillResult(
                    success     = true,
                    data        = ddg.summary,
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    metadata    = mapOf("source" to "duckduckgo")
                )
            } else {
                tool.searchViaIntent(query)
                SkillResult(
                    success     = true,
                    data        = "Search opened in browser for: $query\n\nNote: Add a Brave Search API key in Settings → AI Models → API Keys for full results.",
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    metadata    = mapOf("source" to "browser_intent")
                )
            }
        } catch (e: Exception) {
            SkillResult(false, "", "Web search failed: ${e.message}", skillId)
        }
    }
}
