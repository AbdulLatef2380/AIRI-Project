package com.airi.assistant.ai.skills.impl

import android.content.Context
import com.airi.assistant.ai.skills.AiriSkill
import com.airi.assistant.ai.skills.SkillContext
import com.airi.assistant.ai.skills.SkillMemoryAccess
import com.airi.assistant.ai.skills.SkillModelAccess
import com.airi.assistant.ai.skills.SkillResult
import com.airi.assistant.ai.skills.SkillToolDefinition
import com.airi.assistant.ai.skills.SkillParamDef

class TranslatorSkill(private val context: Context) : AiriSkill {

    override val skillId    = "translator"
    override val name       = "translator"
    override val description = "Translate text between any languages using the active AI model"
    override val version    = "1.0.0"
    override val author     = "AIRI Official"
    override val category   = "PRODUCTIVITY"
    override val iconEmoji  = "🌍"
    override val isOfficial = true
    override val memoryAccess = SkillMemoryAccess.NONE
    override val modelAccess  = SkillModelAccess.CHAT

    override val parameters = mapOf(
        "text"            to "string — text to translate",
        "target_language" to "string — target language (e.g. 'Spanish', 'French', 'Arabic')",
        "source_language" to "string (optional) — source language, auto-detected if omitted"
    )

    override val toolDefinitions = listOf(
        SkillToolDefinition(
            name        = "translate_text",
            description = "Translate text from one language to another",
            parameters  = mapOf(
                "text"            to SkillParamDef("string", "Text to translate", required = true),
                "target_language" to SkillParamDef("string", "Target language name or code", required = true),
                "source_language" to SkillParamDef("string", "Source language (auto-detect if blank)", required = false)
            )
        )
    )

    private val translateKeywords = listOf(
        "translate", "translation", "in french", "in spanish", "in arabic",
        "in german", "in chinese", "in japanese", "in portuguese", "in italian",
        "in korean", "in russian", "how do you say", "what is", "in english",
        "to english", "to french", "to spanish", "to arabic", "to german"
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower = input.lowercase()
        var score = 0
        if (lower.contains("translate")) score += 40
        translateKeywords.forEach { kw -> if (lower.contains(kw)) score += 12 }
        if (context.lastUsedSkill == skillId) score += 10
        return score.coerceAtMost(100)
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val start = System.currentTimeMillis()
        val modelBridge = (params["context"] as? SkillContext)?.modelBridge
            ?: return SkillResult(
                false, "",
                "Translation requires an active AI model. Load a local model or configure a cloud provider in Settings → AI Models.",
                skillId
            )

        val text = params["text"] as? String
            ?: params["input"] as? String
            ?: return SkillResult(false, "", "No text provided to translate.", skillId)

        val targetLang = params["target_language"] as? String ?: extractTargetLanguage(text)
        val sourceLang = params["source_language"] as? String ?: "auto"

        val prompt = buildString {
            if (sourceLang != "auto" && sourceLang.isNotBlank()) {
                append("Translate the following text from $sourceLang to $targetLang.\n")
            } else {
                append("Translate the following text to $targetLang.\n")
            }
            append("Return ONLY the translated text, no explanations.\n\n")
            append("Text to translate:\n$text")
        }

        val systemPrompt = "You are a professional translator. Translate the given text accurately, preserving tone and meaning."

        return try {
            val translated = modelBridge.complete(prompt, systemPrompt, maxTokens = 1024)
            SkillResult(
                success     = true,
                data        = translated,
                skillName   = skillId,
                executionMs = System.currentTimeMillis() - start,
                metadata    = mapOf(
                    "target_language" to targetLang,
                    "source_language" to sourceLang,
                    "original_length" to "${text.length}"
                )
            )
        } catch (e: Exception) {
            SkillResult(false, "", "Translation failed: ${e.message}", skillId)
        }
    }

    private fun extractTargetLanguage(input: String): String {
        val lower = input.lowercase()
        val patterns = mapOf(
            "french" to "French", "spanish" to "Spanish", "german" to "German",
            "arabic" to "Arabic", "chinese" to "Chinese", "japanese" to "Japanese",
            "portuguese" to "Portuguese", "italian" to "Italian", "korean" to "Korean",
            "russian" to "Russian", "hindi" to "Hindi", "dutch" to "Dutch",
            "turkish" to "Turkish", "polish" to "Polish", "swedish" to "Swedish",
            "english" to "English"
        )
        return patterns.entries.firstOrNull { lower.contains(it.key) }?.value ?: "English"
    }
}
