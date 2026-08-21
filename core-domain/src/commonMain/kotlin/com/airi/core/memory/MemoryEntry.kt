package com.airi.core.memory

/**
 * Identifies the owner and optional session to which a memory belongs.
 * Retrieval is deliberately exact-scope by default to avoid cross-user or
 * cross-session context injection.
 */
data class MemoryScope(
    val ownerId: String,
    val sessionId: String? = null
) {
    init {
        require(ownerId.isNotBlank()) { "ownerId must not be blank" }
        require(sessionId?.isNotBlank() != false) { "sessionId must not be blank when provided" }
    }
}

enum class MemoryKind {
    EPISODIC,
    LONG_TERM,
    SEMANTIC,
    WORKING
}

enum class MemoryOutcome {
    UNKNOWN,
    SUCCESS,
    FAILURE
}

/**
 * Platform-neutral memory candidate used by admission, retention, and ranking
 * policies. Storage adapters are responsible for translating their entities to
 * this contract and for performing any physical deletion.
 */
data class MemoryEntry(
    val id: String,
    val scope: MemoryScope,
    val kind: MemoryKind,
    val content: String,
    val tokenCost: Int,
    val createdAtEpochMs: Long,
    val lastAccessedAtEpochMs: Long = createdAtEpochMs,
    val expiresAtEpochMs: Long? = null,
    val deleteEligibleAtEpochMs: Long? = null,
    val relevanceScore: Float = 0f,
    val importanceScore: Float = 0.5f,
    val outcome: MemoryOutcome = MemoryOutcome.UNKNOWN,
    val recallCount: Int = 0
) {
    init {
        require(id.isNotBlank()) { "id must not be blank" }
        require(content.isNotBlank()) { "content must not be blank" }
        require(tokenCost >= 0) { "tokenCost must not be negative" }
        require(createdAtEpochMs >= 0) { "createdAtEpochMs must not be negative" }
        require(lastAccessedAtEpochMs >= createdAtEpochMs) {
            "lastAccessedAtEpochMs must not precede createdAtEpochMs"
        }
        require(expiresAtEpochMs == null || expiresAtEpochMs >= createdAtEpochMs) {
            "expiresAtEpochMs must not precede createdAtEpochMs"
        }
        require(deleteEligibleAtEpochMs == null || deleteEligibleAtEpochMs >= createdAtEpochMs) {
            "deleteEligibleAtEpochMs must not precede createdAtEpochMs"
        }
        require(recallCount >= 0) { "recallCount must not be negative" }
    }
}
