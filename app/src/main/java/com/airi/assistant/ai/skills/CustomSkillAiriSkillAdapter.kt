package com.airi.assistant.ai.skills

import com.airi.assistant.domain.customskill.CustomSkill
import com.airi.assistant.domain.customskill.CustomSkillExecutor

/**
 * CustomSkillAiriSkillAdapter — bridges a [CustomSkill] (HTTP/webhook endpoint)
 * into the [AiriSkill] interface consumed by [SkillRegistry] and [SkillToolBridge].
 *
 * This adapter makes every user-installed custom or marketplace skill available
 * in [SkillRegistry.getAvailableSkills] and therefore accessible from the agent loop.
 *
 * Without this adapter, custom skills were visible in the system prompt
 * ([SkillRegistry.buildSkillDescriptionBlock]) but could never be called by the agent.
 *
 * Scoring is intentionally lightweight — the agent model handles final routing
 * via tool_call JSON based on the tool schema advertised by [SkillToolBridge].
 */
internal class CustomSkillAiriSkillAdapter(
    private val customSkill: CustomSkill,
    private val executor:    CustomSkillExecutor
) : AiriSkill {

    override val skillId:     String  = customSkill.id
    override val name:        String  = customSkill.name
    override val description: String  = customSkill.description
    override val isOfficial:  Boolean = false
    override val iconEmoji:   String  = "🔌"
    override val category:    String  = "CUSTOM"

    override val toolDefinitions: List<SkillToolDefinition> = listOf(
        SkillToolDefinition(
            name        = skillId,
            description = description,
            parameters  = mapOf(
                "input" to SkillParamDef(
                    type        = "string",
                    description = "The user's request or input for the $name skill",
                    required    = true
                )
            )
        )
    )

    override fun score(input: String, context: SkillContext): Int {
        val lower     = input.lowercase()
        val nameLower = name.lowercase()
        val descWords = description.lowercase()
            .split(Regex("\\W+"))
            .filter { it.length > 4 }
        return when {
            lower.contains(nameLower)            -> 70
            descWords.any { lower.contains(it) } -> 35
            else                                 -> 0
        }
    }

    override suspend fun execute(params: Map<String, Any>): SkillResult {
        val input = (params["input"] as? String) ?: ""
        val extras = params
            .filterKeys { it != "input" && it != "context" }
            .mapValues { (_, v) -> v.toString() }
        return executor.execute(
            skill = customSkill,
            input = buildMap {
                put("input", input)
                putAll(extras)
            }
        )
    }
}
