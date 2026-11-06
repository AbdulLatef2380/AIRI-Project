package com.airi.assistant.ai.skills

/** Read-only source of skills exposed to the AgentLoop tool catalog. */
fun interface SkillRoutingSource {
    fun getRegisteredSkillsForRouting(): List<AiriSkill>
}
