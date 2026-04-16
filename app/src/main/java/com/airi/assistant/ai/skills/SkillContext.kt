package com.airi.assistant.ai.skills

data class SkillContext(
    val lastMessages: List<String> = emptyList(),
    val lastAssistantMessage: String? = null,
    val lastUsedSkill: String? = null,
    val userIntentHint: String? = null
)
