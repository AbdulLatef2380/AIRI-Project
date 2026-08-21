package com.airi.core.skills

enum class AiriPlatform {
    ANDROID,
    DESKTOP
}

enum class SkillAvailability {
    READY,
    REQUIRES_PERMISSION,
    UNAVAILABLE
}

data class SkillDescriptor(
    val id: String,
    val displayName: String,
    val intentTags: Set<String>,
    val supportedPlatforms: Set<AiriPlatform>,
    val availability: SkillAvailability,
    val unavailableReason: String? = null
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(displayName.isNotBlank()) { "displayName must not be blank" }
        require(intentTags.none { it.isBlank() }) { "intentTags must not contain blank values" }
        require(supportedPlatforms.isNotEmpty()) { "supportedPlatforms must not be empty" }
        require(
            (availability == SkillAvailability.READY) == unavailableReason.isNullOrBlank()
        ) { "unavailableReason must be present exactly when a skill is not ready" }
    }
}

sealed interface SkillSelectionResult {
    data class Selected(val skill: SkillDescriptor) : SkillSelectionResult
    data class Rejected(val requestedSkillId: String, val reason: String) : SkillSelectionResult
}

object SkillRegistry {

    fun ordered(skills: Iterable<SkillDescriptor>): List<SkillDescriptor> =
        skills.sortedWith(compareBy<SkillDescriptor> { it.displayName.lowercase() }.thenBy { it.id })

    fun select(
        skills: Iterable<SkillDescriptor>,
        requestedSkillId: String,
        platform: AiriPlatform
    ): SkillSelectionResult {
        val skill = skills.firstOrNull { it.id == requestedSkillId }
            ?: return SkillSelectionResult.Rejected(requestedSkillId, "unknown_skill")
        if (platform !in skill.supportedPlatforms) {
            return SkillSelectionResult.Rejected(requestedSkillId, "unsupported_platform")
        }
        if (skill.availability != SkillAvailability.READY) {
            return SkillSelectionResult.Rejected(
                requestedSkillId,
                skill.unavailableReason ?: "skill_not_ready"
            )
        }
        return SkillSelectionResult.Selected(skill)
    }

    fun matching(skills: Iterable<SkillDescriptor>, query: String, platform: AiriPlatform): List<SkillDescriptor> {
        val queryTokens = query.lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .toSet()
        if (queryTokens.isEmpty()) return emptyList()
        return skills.asSequence()
            .filter { platform in it.supportedPlatforms }
            .filter { descriptor -> descriptor.intentTags.any { it.lowercase() in queryTokens } }
            .toList()
            .let(::ordered)
    }
}
