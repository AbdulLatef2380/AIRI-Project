package com.airi.assistant.sync

import com.airi.assistant.memory.entity.MemorySyncMutationEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class CloudSyncOperationIdTest {
    @Test
    fun memoryBatchIdIsStableForTheSameMutationCoordinates() {
        val first = listOf(
            MemorySyncMutationEntity(id = 2L, memoryId = 22L, operation = "UPSERT", updatedAtMs = 100L),
            MemorySyncMutationEntity(id = 1L, memoryId = 11L, operation = "DELETE_TOMBSTONE", updatedAtMs = 90L),
        )
        val second = first.asReversed()

        assertEquals(
            CloudSyncOperationId.memoryBatch("uid", first),
            CloudSyncOperationId.memoryBatch("uid", second),
        )
    }

    @Test
    fun operationIdsDoNotContainMemoryContentOrSecrets() {
        val id = CloudSyncOperationId.memoryBatch(
            "uid",
            listOf(MemorySyncMutationEntity(1L, 7L, "UPSERT", 20L)),
        )

        assertFalse(id.contains("content"))
        assertFalse(id.contains("token"))
        assertEquals("memory:uid:1:7:UPSERT:20", id)
    }

    @Test
    fun differentMutationVersionsProduceDifferentOperations() {
        val old = listOf(MemorySyncMutationEntity(1L, 7L, "UPSERT", 20L))
        val newer = listOf(MemorySyncMutationEntity(1L, 7L, "UPSERT", 21L))

        assertFalse(
            CloudSyncOperationId.memoryBatch("uid", old) ==
                CloudSyncOperationId.memoryBatch("uid", newer),
        )
    }

    @Test
    fun ownerCoordinatesDoNotCollapseDistinctLongUidsToTheSameOperation() {
        val prefix = "u".repeat(80)
        val first = CloudSyncOperationId.profile("$prefix-a", 1L)
        val second = CloudSyncOperationId.profile("$prefix-b", 1L)

        assertFalse(first == second)
    }
}
