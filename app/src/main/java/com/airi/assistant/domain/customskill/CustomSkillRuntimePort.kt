package com.airi.assistant.domain.customskill

/**
 * Runtime capabilities required by a custom-skill executor.
 *
 * Implementations belong to the platform/composition root. The executor must
 * not reach into ServiceLocator or analytics singletons directly.
 */
interface CustomSkillRuntimePort {
    fun isOnline(): Boolean
    fun skillExecuted(skillName: String)
    fun skillFailed(skillName: String, reason: String)
}
