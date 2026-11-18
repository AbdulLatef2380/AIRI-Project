package com.airi.assistant.domain.customskill

data class CustomSkill(
    val id: String,
    val name: String,
    val description: String,
    val type: SkillType,
    val config: SkillConfig,
    val isPremium: Boolean = true,
    val createdAt: Long,
    val permission: SkillPermission? = null,
    // Nullable for Gson compatibility with records written before this field existed.
    val requiredPermissions: List<String>? = null,
) {
    fun effectivePermission(): SkillPermission =
        permission ?: SkillPermissionEnforcer.deriveFromMethod(config.method)
}

enum class SkillType { API, WEBHOOK, LOCAL }

data class SkillConfig(
    val endpoint: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String
)
