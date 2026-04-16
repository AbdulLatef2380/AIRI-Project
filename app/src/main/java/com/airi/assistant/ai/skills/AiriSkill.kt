package com.airi.assistant.ai.skills

interface AiriSkill {
    val name: String
    val description: String
    val parameters: Map<String, String>

    fun score(input: String, context: SkillContext): Int
    suspend fun execute(params: Map<String, Any>): SkillResult
}
