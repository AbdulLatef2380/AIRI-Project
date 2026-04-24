package com.airi.assistant.ai.skills

import android.content.Context

class SkillExecutor(private val context: Context) {

    private val registry = SkillRegistry(context)

    companion object {
        private const val SCORE_THRESHOLD = 20
    }

    suspend fun tryHandle(
        input: String,
        context: SkillContext = SkillContext()
    ): SkillResult? {
        val skills = registry.getAvailableSkills()

        val best = skills
            .map  { skill -> skill to skill.score(input, context) }
            .filter { (_, score) -> score >= SCORE_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?: return null

        val (skill, _) = best

        val params = buildMap<String, Any> {
            put("input", input)
            put("context", context)
        }

        return try {
            val result = skill.execute(params)
            result.copy(skillName = skill.name)
        } catch (e: Exception) {
            SkillResult(
                success   = false,
                data      = "",
                error     = "Skill '${skill.name}' failed: ${e.message ?: "Unknown error"}",
                skillName = skill.name
            )
        }
    }

    fun getRegistry(): SkillRegistry = registry
}
