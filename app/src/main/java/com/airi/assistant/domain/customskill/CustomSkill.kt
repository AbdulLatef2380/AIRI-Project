package com.airi.assistant.domain.customskill

data class CustomSkill(
    val id: String,
    val name: String,
    val description: String,
    val type: SkillType,
    val config: SkillConfig,
    val isPremium: Boolean = true,
    val createdAt: Long
)

enum class SkillType { API, WEBHOOK, LOCAL }

data class SkillConfig(
    val endpoint: String,
    val method: String = "POST",
    val headers: Map<String, String> = emptyMap(),
    val bodyTemplate: String
)