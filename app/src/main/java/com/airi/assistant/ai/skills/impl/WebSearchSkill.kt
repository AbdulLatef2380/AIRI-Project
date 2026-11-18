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
import kotlinx.coroutines.CancellationException
import com.airi.assistant.security.UntrustedContentEnvelope

class WebSearchSkill(private val context: Context) : AiriSkill {

    override val skillId    = "web_search"
    override val name       = "web_search"
    override val description = "Search the web for current information, news, facts, and answers"
    override val version    = "1.1.0"
    override val author     = "AIRI Official"
    override val category   = "SEARCH"
    override val iconEmoji  = ""
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
        val skillContext = params["context"] as? SkillContext
        val tool = SearchTool(context)

        return try {
            val braveUse = skillContext?.secretAccess?.useSecret(
                secretId = "BRAVE_API_KEY",
                operation = "brave_search",
            ) { apiKey ->
                val brave = SearchTool(context, braveApiKey = apiKey).searchBrave(query, count = count, enrich = true)
                if (brave.success) SkillResult(
                        success     = true,
                        data        = UntrustedContentEnvelope.wrap("web-search", brave.toAgentString()),
                        skillName   = skillId,
                        executionMs = System.currentTimeMillis() - start,
                        metadata    = mapOf("source" to "brave", "result_count" to "${brave.results.size}")
                    ) else null
            }
            if (braveUse?.status == com.airi.assistant.vault.SecretVault.CapabilityStatus.CONSUMED && braveUse.value != null) {
                return braveUse.value
            }

            val ddg = tool.searchDuckDuckGo(query)
            if (ddg.success && ddg.summary.isNotBlank()) {
                SkillResult(
                    success     = true,
                    data        = UntrustedContentEnvelope.wrap("web-search", ddg.summary),
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    metadata    = mapOf("source" to "duckduckgo")
                )
            } else {
                tool.searchViaIntent(query)
                SkillResult(
                    success     = true,
                    data        = "Search opened in browser for: $query\n\nFor Brave results, configure BRAVE_API_KEY for the web_search skill in Secret Manager.",
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    metadata    = mapOf("source" to "browser_intent")
                )
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            SkillResult(false, "", "Web search failed: ${e.message}", skillId)
        }
    }
}
