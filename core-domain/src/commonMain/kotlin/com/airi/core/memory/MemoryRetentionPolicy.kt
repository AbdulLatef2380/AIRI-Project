package com.airi.core.memory

enum class MemoryRetentionStatus {
    ACTIVE,
    EXPIRED,
    DELETE_ELIGIBLE
}

object MemoryRetentionPolicy {

    fun status(entry: MemoryEntry, nowEpochMs: Long): MemoryRetentionStatus {
        require(nowEpochMs >= 0) { "nowEpochMs must not be negative" }
        if (entry.deleteEligibleAtEpochMs?.let { nowEpochMs >= it } == true) {
            return MemoryRetentionStatus.DELETE_ELIGIBLE
        }
        if (entry.expiresAtEpochMs?.let { nowEpochMs >= it } == true) {
            return MemoryRetentionStatus.EXPIRED
        }
        return MemoryRetentionStatus.ACTIVE
    }

    fun isActive(entry: MemoryEntry, nowEpochMs: Long): Boolean =
        status(entry, nowEpochMs) == MemoryRetentionStatus.ACTIVE

    fun isDeletionEligible(entry: MemoryEntry, nowEpochMs: Long): Boolean =
        status(entry, nowEpochMs) == MemoryRetentionStatus.DELETE_ELIGIBLE
}
