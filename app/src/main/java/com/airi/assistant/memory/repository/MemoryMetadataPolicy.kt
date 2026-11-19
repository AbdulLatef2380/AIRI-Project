package com.airi.assistant.memory.repository

import com.airi.core.memory.MemoryAdmissionPolicy
import com.airi.assistant.memory.entity.ChatMessage

/**
 * Pure metadata policy shared by memory persistence and retrieval.
 *
 * Metadata is not allowed to weaken [MemoryAdmissionPolicy]: provenance is a
 * short explanation of why an already-approved fact was saved, not a second
 * copy of the original prompt or an unbounded diagnostic channel.
 */
enum class MemoryScope {
    SESSION,
    PROJECT,
    USER
}

/** Numeric memory sensitivity ceilings: local may use all levels; remote is limited to level 1. */
object MemoryPrivacyPolicy {
    const val MAX_LEVEL = 3
    const val REMOTE_MAX_LEVEL = 1
    const val LOCAL_MAX_LEVEL = MAX_LEVEL

    fun retrievalCeiling(localOnly: Boolean): Int =
        if (localOnly) LOCAL_MAX_LEVEL else REMOTE_MAX_LEVEL
}

internal object MemoryMetadataPolicy {
    private const val MAX_PROVENANCE_CHARS = 180
    private const val MIN_PRIVACY_LEVEL = 0
    private const val MAX_PRIVACY_LEVEL = MemoryPrivacyPolicy.MAX_LEVEL

    fun normalizeScope(scope: MemoryScope, projectId: String): String = when (scope) {
        MemoryScope.PROJECT -> if (projectId.isBlank()) MemoryScope.SESSION.name else MemoryScope.PROJECT.name
        MemoryScope.SESSION,
        MemoryScope.USER -> scope.name
    }

    fun normalizePrivacyLevel(value: Int): Int = value.coerceIn(MIN_PRIVACY_LEVEL, MAX_PRIVACY_LEVEL)

    fun normalizeImportance(value: Int): Int = value.coerceIn(0, 100)

    fun sanitizeProvenance(value: String): String {
        val normalized = value.replace(Regex("\\s+"), " ").trim().take(MAX_PROVENANCE_CHARS)
        return if (normalized.isBlank() || MemoryAdmissionPolicy.containsSensitiveData(normalized)) {
            "Explicit user memory request"
        } else {
            normalized
        }
    }

    /** The single ownership, privacy, and expiry rule used by every memory read path. */
    fun isVisible(
        memory: ChatMessage,
        sessionId: String,
        projectId: String,
        maxPrivacyLevel: Int,
        nowMs: Long,
    ): Boolean {
        if (!memory.isMemory || memory.privacyLevel > normalizePrivacyLevel(maxPrivacyLevel)) return false
        if (memory.expiresAtMs >= 0L && memory.expiresAtMs <= nowMs) return false
        return when (memory.memoryScope) {
            MemoryScope.USER.name -> true
            MemoryScope.PROJECT.name -> projectId.isNotBlank() && memory.projectId == projectId
            MemoryScope.SESSION.name -> memory.sessionId == sessionId
            else -> false
        }
    }
}
