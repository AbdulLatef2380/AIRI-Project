package com.airi.assistant.ai.skills

import com.airi.assistant.memory.repository.MemoryManager

/**
 * SkillContext — runtime context passed to every skill execution.
 *
 * Contains conversation history, memory bridge, optional model bridge, and
 * any other environmental data a skill needs during [AiriSkill.score] and
 * [AiriSkill.execute].
 */
data class SkillContext(
    val lastMessages:          List<String>       = emptyList(),
    val lastAssistantMessage:  String?            = null,
    val lastUsedSkill:         String?            = null,
    val userIntentHint:        String?            = null,
    val memoryManager:         MemoryManager?     = null,
    val modelBridge:           SkillModelBridge?  = null,
    val sessionId:             String             = "",
    val configValues:          Map<String, String> = emptyMap()
)
