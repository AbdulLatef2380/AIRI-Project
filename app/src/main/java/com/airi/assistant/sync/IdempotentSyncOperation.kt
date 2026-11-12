package com.airi.assistant.sync

/**
 * Backend boundary for a logical sync operation.
 *
 * Implementations must make [commit] safe to replay for the same operationId.
 * The caller never treats a write as successful until [isApplied] confirms it.
 */
interface IdempotentSyncTransport {
    suspend fun commit(operationId: String, payload: Map<String, String>)
    suspend fun isApplied(operationId: String): Boolean
}

sealed interface SyncOperationOutcome {
    data object AlreadyApplied : SyncOperationOutcome
    data object CommittedAndVerified : SyncOperationOutcome
    data object CommittedAfterAmbiguousAck : SyncOperationOutcome
    data object UnknownCommit : SyncOperationOutcome
    data class Rejected(val reason: String) : SyncOperationOutcome
}

/**
 * Deterministic write/reconcile state machine used by sync adapters and JVM tests.
 *
 * A timeout or transport exception is not converted into success. The operation
 * remains retryable until a read-after-write proves that the backend applied the
 * same logical operation. Replaying an operation is therefore safe, while an
 * unknown commit is never acknowledged locally.
 */
class IdempotentSyncOperationExecutor(
    private val transport: IdempotentSyncTransport,
) {
    suspend fun execute(
        operationId: String,
        payload: Map<String, String>,
    ): SyncOperationOutcome {
        require(operationId.isNotBlank()) { "operationId is required" }

        if (runCatching { transport.isApplied(operationId) }.getOrDefault(false)) {
            return SyncOperationOutcome.AlreadyApplied
        }

        return try {
            transport.commit(operationId, payload)
            if (transport.isApplied(operationId)) {
                SyncOperationOutcome.CommittedAndVerified
            } else {
                SyncOperationOutcome.UnknownCommit
            }
        } catch (error: Exception) {
            if (runCatching { transport.isApplied(operationId) }.getOrDefault(false)) {
                SyncOperationOutcome.CommittedAfterAmbiguousAck
            } else {
                SyncOperationOutcome.UnknownCommit
            }
        }
    }
}
