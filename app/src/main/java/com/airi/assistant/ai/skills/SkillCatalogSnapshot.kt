package com.airi.assistant.ai.skills

/** Immutable capability view captured at execution admission time. */
data class SkillCatalogSnapshot(
    val version: Long,
    val skills: List<AiriSkill>,
    val registeredIds: Set<String>,
) {
    init {
        require(skills.map { it.skillId }.toSet() == registeredIds) {
            "Skill snapshot ids must match its immutable skill list"
        }
    }
}
