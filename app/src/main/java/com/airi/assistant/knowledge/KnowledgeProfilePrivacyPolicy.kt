package com.airi.assistant.knowledge

import com.airi.assistant.memory.repository.MemoryPrivacyPolicy

/**
 * Knowledge profiles are user-scoped. A profile is eligible only when its
 * recorded sensitivity is at or below the active model's retrieval ceiling.
 * Legacy profiles default to the most restrictive local-only level.
 */
object KnowledgeProfilePrivacyPolicy {
    const val DEFAULT_LEVEL = MemoryPrivacyPolicy.MAX_LEVEL
    const val CLOUD_CONTEXT_LEVEL = MemoryPrivacyPolicy.REMOTE_MAX_LEVEL

    fun isAllowed(profilePrivacyLevel: Int, maxPrivacyLevel: Int): Boolean =
        profilePrivacyLevel.coerceIn(0, MemoryPrivacyPolicy.MAX_LEVEL) <=
            maxPrivacyLevel.coerceIn(0, MemoryPrivacyPolicy.MAX_LEVEL)
}
