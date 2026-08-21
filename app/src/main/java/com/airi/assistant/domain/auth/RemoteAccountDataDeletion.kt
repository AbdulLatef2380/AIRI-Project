package com.airi.assistant.domain.auth

sealed interface RemoteAccountDataDeletionResult {
    data object Deleted : RemoteAccountDataDeletionResult
    data class Unavailable(val message: String) : RemoteAccountDataDeletionResult
    data class Failed(val message: String) : RemoteAccountDataDeletionResult
}

fun interface RemoteAccountDataDeletion {
    suspend fun deleteOwnedData(ownerId: String): RemoteAccountDataDeletionResult
}

object UnavailableRemoteAccountDataDeletion : RemoteAccountDataDeletion {
    override suspend fun deleteOwnedData(ownerId: String): RemoteAccountDataDeletionResult =
        RemoteAccountDataDeletionResult.Unavailable(
            "Cloud data deletion is not configured for this installation."
        )
}
