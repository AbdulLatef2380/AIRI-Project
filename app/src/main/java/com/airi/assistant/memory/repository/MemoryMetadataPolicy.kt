package com.airi.assistant.memory.repository

import com.airi.core.memory.MemoryAdmissionPolicy

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

internal object MemoryMetadataPolicy {
    private const val MAX_PROVENANCE_CHARS = 180
    private const val MIN_PRIVACY_LEVEL = 0
    private const val MAX_PRIVACY_LEVEL = 3

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
}
