package com.airi.assistant.sync

import com.airi.assistant.memory.entity.MemorySyncMutationEntity

/**
 * Stable logical operation identifiers used for sync telemetry and reconciliation.
 *
 * IDs contain only the owner coordinate and mutation metadata. They never include
 * profile values, memory contents, prompts, credentials, or paths.
 */
internal object CloudSyncOperationId {
    fun profile(uid: String, lastUpdatedAtMs: Long): String =
        "profile:${owner(uid)}:$lastUpdatedAtMs"

    fun memoryBatch(uid: String, mutations: List<MemorySyncMutationEntity>): String =
        "memory:${owner(uid)}:" + mutations
            .map { "${it.id}:${it.memoryId}:${it.operation}:${it.updatedAtMs}" }
            .sorted()
            .joinToString(",")

    fun taskContinuity(uid: String, taskIdsAndVersions: List<String>): String =
        "task_continuity:${owner(uid)}:" + taskIdsAndVersions.sorted().joinToString(",")

    /** UIDs are coordinates, not secrets; preserve the full owner scope to avoid prefix collisions. */
    private fun owner(uid: String): String = uid.trim().also {
        require(it.isNotBlank()) { "uid is required" }
    }
}
