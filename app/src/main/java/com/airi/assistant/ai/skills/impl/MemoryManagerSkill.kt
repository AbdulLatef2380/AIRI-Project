package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef

class MemoryManagerSkill(private val context: Context) : AiriSkill {

    override val skillId    = "memory_manager"
    override val name       = "memory_manager"
    override val description = "Search, read, and save information to AIRI's persistent memory across conversations"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "AI"
    override val iconEmoji  = "🧠"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.FULL_ACCESS
    override val modelAccess  = SkillModelAccess.NONE

    override val parameters = mapOf(
        "action" to "string — 'recall', 'save', or 'search'",
        "query"  to "string — search query for recall/search",
        "content" to "string — text to save (for 'save' action)",
        "limit"  to "int (optional) — max results to return, default 10"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "memory_recall",
            description = "Recall recent or semantically relevant memories",
            parameters  = mapOf(
                "query" to SkillParamDef("string", "What to search for in memory", required = true),
                "limit" to SkillParamDef("int", "Max results (default 10)", required = false)
            )
        ),
        SkillToolDefinition(
            name        = "memory_save",
            description = "Save a fact or important information to long-term memory",
            parameters  = mapOf(
                "content" to SkillParamDef("string", "Text to save to memory", required = true)
            )
        )
    )

    private val memoryKeywords = listOf(
        "remember", "recall", "memory", "save to memory", "what did i",
        "do you remember", "forget", "memorize", "store", "previously",
        "last time", "earlier you said", "in my memory"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        if (lower.contains("remember") || lower.contains("recall")) score += 35
        memoryKeywords.forEach { kw -> if (lower.contains(kw)) score += 12 }
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start    = System.currentTimeMillis()
        val skillCtx = params["context"] as? SkillContext
        val manager  = skillCtx?.memoryManager
            ?: return SkillResult(
                false, "",
                "Memory service is not available in this session.",
                skillId
            )

        val input  = params["input"] as? String ?: ""
        val action = (params["action"] as? String ?: detectAction(input)).lowercase()
        val limit  = (params["limit"] as? String)?.toIntOrNull() ?: 10

        return when (action) {
            "save" -> {
                val content = params["content"] as? String
                    ?: params["input"] as? String
                    ?: return SkillResult(false, "", "No content provided to save.", skillId)
                try {
                    if (!manager.canStoreImportantMemory(content)) {
                        return SkillResult(
                            success = false,
                            data = "",
                            error = "This content is sensitive or empty and was not saved to long-term memory.",
                            skillName = skillId
                        )
                    }
                    manager.recordImportantMemory(
                        role = "user",
                        content = content,
                        explicitlyRequested = true,
                        sessionId = skillCtx.sessionId.ifBlank { "default" }
                    )
                    SkillResult(
                        success     = true,
                        data        = "Saved to memory: \"${content.take(100)}${if (content.length > 100) "…" else ""}\"",
                        skillName   = skillId,
                        executionMs = System.currentTimeMillis() - start
                    )
                } catch (e: Exception) {
                    SkillResult(false, "", "Failed to save to memory: ${e.message}", skillId)
                }
            }

            "recall", "search" -> {
                val query = params["query"] as? String ?: input
                if (query.isBlank()) {
                    return SkillResult(false, "", "No query provided for memory recall.", skillId)
                }
                try {
                    val sessionId = skillCtx.sessionId
                    val results = if (manager.isSemanticMemoryReady() && sessionId.isNotEmpty()) {
                        manager.semanticSearch(sessionId, query, limit).map { it.message }
                    } else {
                        manager.getRecentMessages(limit)
                    }

                    if (results.isEmpty()) {
                        SkillResult(
                            success     = true,
                            data        = "No memories found for: \"$query\"",
                            skillName   = skillId,
                            executionMs = System.currentTimeMillis() - start
                        )
                    } else {
                        val formatted = buildString {
                            append("Memory results for \"$query\":\n\n")
                            results.forEachIndexed { i, msg ->
                                append("${i + 1}. [${msg.role}] ${msg.content.take(200)}\n")
                            }
                        }
                        SkillResult(
                            success     = true,
                            data        = formatted,
                            skillName   = skillId,
                            executionMs = System.currentTimeMillis() - start,
                            metadata    = mapOf("results" to "${results.size}", "query" to query.take(80))
                        )
                    }
                } catch (e: Exception) {
                    SkillResult(false, "", "Memory recall failed: ${e.message}", skillId)
                }
            }

            else -> SkillResult(
                false, "",
                "Unknown memory action: $action. Valid actions: 'recall', 'save', 'search'",
                skillId
            )
        }
    }

    private fun detectAction(input: String): String {
        val lower = input.lowercase()
        return when {
            lower.contains("save") || lower.contains("remember this") ||
            lower.contains("store") || lower.contains("memorize") -> "save"
            else -> "recall"
        }
    }
}
