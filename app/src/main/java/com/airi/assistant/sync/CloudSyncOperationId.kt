package com.airi.assistant.sync

import com.airi.assistant.memory.entity.MemorySyncMutationEntity

/**
 * Stable logical operation identifiers used for sync telemetry and reconciliation.
 *
 * IDs contain only ownership-neutral coordinates and mutation metadata. They never
 * include profile values, memory contents, prompts, credentials, or paths.
 */
internal object CloudSyncOperationId {
    fun profile(uid: String, lastUpdatedAtMs: Long): String =
        "profile:${uid.take(64)}:$lastUpdatedAtMs"

    fun memoryBatch(uid: String, mutations: List<MemorySyncMutationEntity>): String =
        "memory:${uid.take(64)}:" + mutations
            .map { "${it.id}:${it.memoryId}:${it.operation}:${it.updatedAtMs}" }
            .sorted()
            .joinToString(",")

    fun taskContinuity(uid: String, taskIdsAndVersions: List<String>): String =
        "task_continuity:${uid.take(64)}:" + taskIdsAndVersions.sorted().joinToString(",")
}
