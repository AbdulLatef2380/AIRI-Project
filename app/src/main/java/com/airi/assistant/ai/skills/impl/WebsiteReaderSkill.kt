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

class WebsiteReaderSkill(private val context: Context) : AiriSkill {

    override val skillId    = "website_reader"
    override val name       = "website_reader"
    override val description = "Fetch and extract the full text content of any web page URL"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "SEARCH"
    override val iconEmoji  = "🌐"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.READ_WRITE
    override val modelAccess  = SkillModelAccess.NONE

    override val parameters = mapOf(
        "url"      to "string — the full URL to read",
        "maxChars" to "int (optional) — max characters to return, default 4000"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "fetch_url",
            description = "Fetch and read the full text content of a web page",
            parameters  = mapOf(
                "url"      to SkillParamDef("string", "Full URL (must start with https://)", required = true),
                "maxChars" to SkillParamDef("int", "Max characters to return (default 4000)", required = false)
            )
        )
    )

    private val urlKeywords = listOf(
        "read", "fetch", "open", "visit", "go to", "load",
        "content of", "text from", "extract from", "scrape",
        "http://", "https://", "www."
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        if (lower.contains("http://") || lower.contains("https://") || lower.contains("www.")) score += 50
        urlKeywords.forEach { kw -> if (lower.contains(kw)) score += 10 }
        if (context.lastUsedSkill == skillId) score += 15
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start = System.currentTimeMillis()
        val url = (params["url"] as? String
            ?: extractUrl(params["input"] as? String ?: ""))?.trim()
            ?: return SkillResult(false, "", "No URL provided.", skillId)

        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return SkillResult(false, "", "URL must start with http:// or https://", skillId)
        }

        val maxChars = (params["maxChars"] as? String)?.toIntOrNull() ?: 4000
        val tool = SearchTool(context)

        return try {
            val jina = tool.fetchViaJina(url, maxChars = maxChars)
            if (jina.success && jina.content.isNotBlank()) {
                SkillResult(
                    success     = true,
                    data        = "Content from $url:\n\n${jina.content}",
                    skillName   = skillId,
                    executionMs = System.currentTimeMillis() - start,
                    metadata    = mapOf("url" to url, "source" to "jina_reader", "chars" to "${jina.content.length}")
                )
            } else {
                val direct = tool.fetchPageContent(url)
                if (direct.success && direct.content.isNotBlank()) {
                    SkillResult(
                        success     = true,
                        data        = "Content from $url:\n\n${direct.content.take(maxChars)}",
                        skillName   = skillId,
                        executionMs = System.currentTimeMillis() - start,
                        metadata    = mapOf("url" to url, "source" to "direct_fetch")
                    )
                } else {
                    SkillResult(false, "", "Could not fetch content from: $url", skillId)
                }
            }
        } catch (e: Exception) {
            SkillResult(false, "", "Failed to read website: ${e.message}", skillId)
        }
    }

    private fun extractUrl(input: String): String? {
        val regex = Regex("""https?://[^\s]+""")
        return regex.find(input)?.value
    }
}
