package com.airi.assistant.domain.auth

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Source
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull

/**
 * P1/P2 — real cloud deletion.
 *
 * Firestore rules forbid the device from deleting most cloud data, so it files a request in
 * `accountDeletionRequests` and waits for the `processAccountDeletionRequest` Cloud Function
 * (Admin SDK) to report the verdict. "Deleted" is returned only when the server says
 * `completed`; no answer in time or any error is a failure, never a silent success, so the
 * coordinator keeps the Firebase account and local data for a safe retry.
 *
 * Requires: the function and the updated `firestore.rules` to be deployed.
 */
class FirestoreAccountDataDeletion(
    private val firestoreProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() },
    private val timeoutMs: Long = 90_000L,
    private val pollIntervalMs: Long = 2_000L,
) : RemoteAccountDataDeletion {

    /** Everything the account owns in the cloud. */
    override suspend fun deleteOwnedData(ownerId: String): RemoteAccountDataDeletionResult =
        request(ownerId, KIND_ACCOUNT)

    /** P2: only the synced memory records; the account stays. */
    suspend fun deleteCloudMemory(ownerId: String): RemoteAccountDataDeletionResult =
        request(ownerId, KIND_MEMORY)

    private suspend fun request(ownerId: String, kind: String): RemoteAccountDataDeletionResult {
        if (ownerId.isBlank()) return RemoteAccountDataDeletionResult.Failed("Sign in is required.")
        val firestore = try {
            firestoreProvider()
        } catch (e: IllegalStateException) {
            return RemoteAccountDataDeletionResult.Unavailable("Cloud services are not configured for this installation.")
        }
        return try {
            val ref = firestore.collection(COLLECTION).document()
            ref.set(
                mapOf(
                    "uid" to ownerId,
                    "kind" to kind,
                    "status" to "pending",
                    "createdAt" to Timestamp.now(),
                )
            ).await()

            val verdict = withTimeoutOrNull(timeoutMs) {
                var result: RemoteAccountDataDeletionResult? = null
                while (result == null) {
                    val doc = ref.get(Source.SERVER).await()
                    result = when (doc.getString("status")) {
                        "completed" -> RemoteAccountDataDeletionResult.Deleted
                        "failed" -> RemoteAccountDataDeletionResult.Failed(
                            doc.getString("reason") ?: "Cloud data deletion failed. Please try again."
                        )
                        else -> null
                    }
                    if (result == null) delay(pollIntervalMs)
                }
                result
            }
            verdict ?: RemoteAccountDataDeletionResult.Failed(
                "Cloud deletion was requested but has not been confirmed yet. Nothing was removed from this device; please try again later."
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AIRI CLOUD_DELETE_REQUEST_FAILED type=${e.javaClass.simpleName}")
            RemoteAccountDataDeletionResult.Failed("Could not reach the deletion service. Check your connection and try again.")
        }
    }

    private companion object {
        const val TAG = "AIRI_CloudDeletion"
        const val COLLECTION = "accountDeletionRequests"
        const val KIND_ACCOUNT = "account"
        const val KIND_MEMORY = "memory"
    }
}
