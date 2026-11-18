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
    /** Internal AgentLoop owner used only for runtime trace correlation. */
    val executionId:           String             = "",
    val configValues:          Map<String, String> = emptyMap(),
    /** Available only to trusted first-party skills; each use requires its own approval. */
    val secretAccess: SkillSecretAccess? = null,
)

/**
 * A skill can ask a trusted adapter to use one of its own configured secrets.
 * No getter exposes the stored value; each use is separately approved and
 * backed by a short-lived, one-use SecretVault capability.
 */
interface SkillSecretAccess {
    suspend fun <T> useSecret(
        secretId: String,
        operation: String,
        consumer: suspend (String) -> T,
    ): com.airi.assistant.vault.SecretVault.CapabilityResult<T>
}
