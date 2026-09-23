package com.airi.assistant.ai.skills

/**
 * Lightweight, deterministic postcondition gate for every skill result.
 *
 * This does not claim factual correctness. It verifies only execution-level
 * invariants that are safe to enforce centrally: successful results must carry
 * non-empty output, failed results must carry an error, and the skill identity
 * must be preserved for telemetry and UI tracing.
 */
object SkillResultVerifier {
    fun verify(skill: AiriSkill, result: SkillResult): SkillResult {
        if (result.success && result.data.isBlank()) {
            return result.copy(
                success = false,
                error = "Skill returned success without output.",
                skillName = result.skillName ?: skill.skillId,
                metadata = result.metadata + ("result_verified" to "false")
            )
        }
        if (!result.success && result.error.isNullOrBlank()) {
            return result.copy(
                error = "Skill execution failed without an error message.",
                skillName = result.skillName ?: skill.skillId,
                metadata = result.metadata + ("result_verified" to "false")
            )
        }
        return result.copy(
            skillName = result.skillName ?: skill.skillId,
            metadata = result.metadata + ("result_verified" to "true")
        )
    }
}
