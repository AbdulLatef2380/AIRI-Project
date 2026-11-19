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

private const val RESEARCH_RESULT_MAX_CHARS = 4000

/** Keeps both ends of a bounded research result and explicitly reports omitted content. */
internal fun truncateResearchResult(rawSources: String, maxChars: Int = RESEARCH_RESULT_MAX_CHARS): String {
    require(maxChars > 0) { "maxChars must be positive" }
    if (rawSources.length <= maxChars) return rawSources

    val markerTemplate = "[TRUNCATED omitted_chars=%d original_chars=%d kept_prefix=%d kept_suffix=%d]"
    val markerWithoutLengths = markerTemplate.format(0, rawSources.length, 0, 0)
    val availableForContent = (maxChars - markerWithoutLengths.length - 2).coerceAtLeast(0)
    val prefixLength = availableForContent / 2
    val suffixLength = availableForContent - prefixLength
    val marker = markerTemplate.format(
        rawSources.length - prefixLength - suffixLength,
        rawSources.length,
        prefixLength,
        suffixLength,
    )
    val actualAvailableForContent = (maxChars - marker.length - 2).coerceAtLeast(0)
    val actualPrefixLength = minOf(prefixLength, actualAvailableForContent / 2)
    val actualSuffixLength = minOf(suffixLength, actualAvailableForContent - actualPrefixLength)
    val actualMarker = markerTemplate.format(
        rawSources.length - actualPrefixLength - actualSuffixLength,
        rawSources.length,
        actualPrefixLength,
        actualSuffixLength,
    )
    return buildString(maxChars) {
        append(rawSources, 0, actualPrefixLength)
        append('\n')
        append(actualMarker)
        append('\n')
        append(rawSources, rawSources.length - actualSuffixLength, rawSources.length)
    }
}

/**
 * ResearchAgentSkill — multi-step research: searches the web, fetches top pages,
 * synthesizes a comprehensive answer using the active model.
 */
class ResearchAgentSkill(private val context: Context) : AiriSkill {
    private data class BraveResearchBundle(
        val rawSources: String,
        val toolOutputs: List<SkillResult.ToolOutput>,
    )

    override val skillId    = "research_agent"
    override val name       = "research_agent"
    override val description = "Deep research assistant: searches multiple sources, reads web pages, and synthesizes a comprehensive answer"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "AI"
    override val iconEmoji  = ""
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

        val query = (params["query"] as? String
            ?: params["input"] as? String
            ?: "").trim()

        if (query.isBlank()) {
            return SkillResult(false, "", "Research query cannot be empty.", skillId)
        }

        val depth = (params["depth"] as? String)?.toIntOrNull()?.coerceIn(1, 5) ?: 3
        val tool  = SearchTool(context)
        val rawSources = StringBuilder()
        val toolOutputs = mutableListOf<SkillResult.ToolOutput>()

        // Step 1: Search
        val braveUse = try {
            skillCtx?.secretAccess?.useSecret(
                secretId = "BRAVE_API_KEY",
                operation = "deep_research_search",
            ) { apiKey ->
                val braveTool = SearchTool(context, braveApiKey = apiKey)
                val brave = braveTool.searchBrave(query, count = depth, enrich = true)
                if (brave.success) {
                    val sources = StringBuilder().append("=== Search Results ===\n${brave.toAgentString()}\n\n")
                    val outputs = mutableListOf(SkillResult.ToolOutput("web_search", brave.toAgentString()))

                    // Step 2: Fetch top pages for deeper content
                    val urlsToFetch = brave.results.take(minOf(depth, 3)).mapNotNull { it.url }
                    urlsToFetch.forEachIndexed { idx, url ->
                        val page = braveTool.fetchViaJina(url, maxChars = 2000)
                        if (page.success && page.content.isNotBlank()) {
                            sources.append("=== Source ${idx + 1}: $url ===\n${page.content}\n\n")
                            outputs.add(SkillResult.ToolOutput("fetch_url", page.content))
                        }
                    }
                    BraveResearchBundle(sources.toString(), outputs)
                } else null
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            null
        }

        val braveBundle = braveUse?.value.takeIf {
            braveUse?.status == com.airi.assistant.vault.SecretVault.CapabilityStatus.CONSUMED
        }
        if (braveBundle != null) {
            rawSources.append(braveBundle.rawSources)
            toolOutputs.addAll(braveBundle.toolOutputs)
        }

        if (braveBundle == null) {
            val ddg = tool.searchDuckDuckGo(query)
            if (ddg.success) {
                rawSources.append("=== Search Results ===\n${ddg.summary}\n\n")
                toolOutputs.add(SkillResult.ToolOutput("web_search", ddg.summary))
            }
        }

        if (rawSources.isBlank()) {
            return SkillResult(
                false, "",
                "Could not retrieve research sources for: $query\n\nTip: Configure BRAVE_API_KEY for the research_agent skill in Secret Manager for Brave results.",
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
                    data        = "Research results for: $query\n\n${truncateResearchResult(rawSources.toString())}",
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    toolOutputs = toolOutputs
                )
            }
        }

        return SkillResult(
            success     = true,
            data        = "Research results for: $query\n\n${truncateResearchResult(rawSources.toString())}\n\n[Note: Load an AI model to get a synthesized summary]",
            skillName   = skillId,
            executionMs = System.currentTimeMillis() - start,
            toolOutputs = toolOutputs
        )
    }
}
