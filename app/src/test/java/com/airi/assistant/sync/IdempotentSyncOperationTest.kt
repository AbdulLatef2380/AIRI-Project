package com.airi.assistant.sync

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IdempotentSyncOperationTest {

    @Test
    fun commitIsAcknowledgedOnlyAfterReadAfterWriteVerification() = runBlocking {
        val transport = FakeTransport()
        val executor = IdempotentSyncOperationExecutor(transport)

        assertEquals(
            SyncOperationOutcome.CommittedAndVerified,
            executor.execute("op-1", mapOf("version" to "1")),
        )
        assertEquals(1, transport.commitCount)
        assertEquals(2, transport.readCount)
    }

    @Test
    fun timeoutAfterServerCommitIsClassifiedAsAmbiguousButRetryIsAlreadyApplied() = runBlocking {
        val transport = FakeTransport().apply { throwAfterCommit = true }
        val firstAttempt = IdempotentSyncOperationExecutor(transport)

        assertEquals(
            SyncOperationOutcome.CommittedAfterAmbiguousAck,
            firstAttempt.execute("op-timeout", mapOf("version" to "7")),
        )
        assertEquals(1, transport.commitCount)

        // Simulates a new WorkManager process after process death. The same
        // logical operation is reconciled without a second side effect.
        transport.throwAfterCommit = false
        val restartedAttempt = IdempotentSyncOperationExecutor(transport)
        assertEquals(
            SyncOperationOutcome.AlreadyApplied,
            restartedAttempt.execute("op-timeout", mapOf("version" to "7")),
        )
        assertEquals(1, transport.commitCount)
    }

    @Test
    fun failedTransportWithoutBackendEvidenceRemainsUnknownAndRetryable() = runBlocking {
        val transport = FakeTransport().apply { failBeforeCommit = true }
        val executor = IdempotentSyncOperationExecutor(transport)

        assertEquals(
            SyncOperationOutcome.UnknownCommit,
            executor.execute("op-offline", mapOf("version" to "2")),
        )
        assertTrue(transport.applied.isEmpty())
    }

    @Test
    fun replayOfTwoLogicalOperationsKeepsPartialFailureIsolated() = runBlocking {
        val transport = FakeTransport().apply { failIds += "op-2" }
        val executor = IdempotentSyncOperationExecutor(transport)

        assertEquals(
            SyncOperationOutcome.CommittedAndVerified,
            executor.execute("op-1", mapOf("row" to "1")),
        )
        assertEquals(
            SyncOperationOutcome.UnknownCommit,
            executor.execute("op-2", mapOf("row" to "2")),
        )
        assertTrue("a failed operation must not erase an earlier commit", transport.applied.contains("op-1"))
        assertTrue("the failed operation remains unacknowledged", !transport.applied.contains("op-2"))
    }

    private class FakeTransport : IdempotentSyncTransport {
        val applied = linkedSetOf<String>()
        val failIds = linkedSetOf<String>()
        var throwAfterCommit = false
        var failBeforeCommit = false
        var commitCount = 0
        var readCount = 0

        override suspend fun commit(operationId: String, payload: Map<String, String>) {
            commitCount++
            if (failBeforeCommit || operationId in failIds) error("transport unavailable")
            applied += operationId
            if (throwAfterCommit) error("ack lost after server commit")
        }

        override suspend fun isApplied(operationId: String): Boolean {
            readCount++
            return operationId in applied
        }
    }
}
