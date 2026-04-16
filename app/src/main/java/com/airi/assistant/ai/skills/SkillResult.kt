package com.airi.assistant.ai.skills

data class SkillResult(
    val success: Boolean,
    val data: String,
    val error: String? = null,
    val skillName: String? = null
)
