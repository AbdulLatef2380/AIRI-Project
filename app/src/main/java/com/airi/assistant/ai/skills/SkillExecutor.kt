package com.airi.assistant.ai.skills

import android.content.Context
import com.airi.assistant.core.AiriLogger

class SkillExecutor(private val context: Context) {

    private val registry = SkillRegistry(context)

    companion object {
        private const val SCORE_THRESHOLD = 20

        val SENSITIVE_SKILLS = setOf(
            "telegram_messenger",
            "gmail_assistant",
            "calendar_events"
        )
    }

    fun isSensitive(input: String): Boolean {
        val skills = registry.getAvailableSkills()
        val best = skills
            .map { skill -> skill to skill.score(input, SkillContext()) }
            .filter { (_, score) -> score >= SCORE_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?: return false
        return SENSITIVE_SKILLS.contains(best.first.name)
    }

    suspend fun tryHandle(
        input: String,
        skillContext: SkillContext = SkillContext()
    ): SkillResult? {
        val skills = registry.getAvailableSkills()

        val best = skills
            .map { skill -> skill to skill.score(input, skillContext) }
            .filter { (_, score) -> score >= SCORE_THRESHOLD }
            .maxByOrNull { (_, score) -> score }
            ?: return null

        val (skill, score) = best
        AiriLogger.agent("SkillMatch", "skill=${skill.name} score=$score input=${input.take(60)}")

        val params = buildMap<String, Any> {
            put("input", input)
            put("context", skillContext)
        }

        return try {
            AiriLogger.skill(skill.name, input, success = true)
            val result = skill.execute(params)
            if (!result.success) {
                AiriLogger.skill(skill.name, input, success = false)
                AiriLogger.apiFail(skill.name, result.error ?: "no error detail")
                result.copy(
                    skillName = skill.name,
                    error = result.error ?: "The action could not be completed. Please try again."
                )
            } else {
                result.copy(skillName = skill.name)
            }
        } catch (e: Exception) {
            AiriLogger.e("Skill '${skill.name}' threw exception: ${e.message}", e)
            SkillResult(
                success   = false,
                data      = "",
                error     = "Could not complete this action. Please check your connection and try again.",
                skillName = skill.name
            )
        }
    }

    fun getRegistry(): SkillRegistry = registry
}
