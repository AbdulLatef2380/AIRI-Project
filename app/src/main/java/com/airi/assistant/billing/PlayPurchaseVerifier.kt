package com.airi.assistant.billing

import android.util.Log
import com.google.firebase.Timestamp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * B2 — server-side verification of Google Play purchases.
 *
 * The client NEVER decides that a purchase is valid. It files a claim at
 * `users/{uid}/purchaseClaims/{id}` (status = "pending"); a trusted Cloud Function
 * (functions/index.js → verifyPlayPurchaseClaim) checks the purchaseToken against the
 * Google Play Developer API and writes the verdict back. Firestore rules forbid clients from
 * writing `status`, so the verdict cannot be forged from the device.
 */
class PlayPurchaseVerifier(
    authProvider: () -> FirebaseAuth = { FirebaseAuth.getInstance() },
    dbProvider: () -> FirebaseFirestore = { FirebaseFirestore.getInstance() }
) {
    // Lazy: constructing BillingManager must not force Firebase initialisation.
    private val auth by lazy(authProvider)
    private val db by lazy(dbProvider)


    sealed class Result {
        /** Server confirmed an active subscription. [expiresAtMillis] comes from Google Play. */
        data class Verified(val expiresAtMillis: Long, val verifiedAtMillis: Long) : Result()
        /** Server examined the purchase and refused it. Never grant. */
        data class Rejected(val reason: String) : Result()
        /** Could not reach a verdict (signed out, offline, timeout). Never grant; retry later. */
        data class Unavailable(val reason: String) : Result()
    }

    suspend fun verify(
        packageName: String,
        productId: String,
        purchaseToken: String,
        timeoutMs: Long = 30_000L
    ): Result {
        val uid = auth.currentUser?.uid
            ?: return Result.Unavailable("Sign in to activate your subscription.")

        val claim: DocumentReference = db.collection("users").document(uid)
            .collection("purchaseClaims").document()
        val outcome = try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine<Result> { cont ->
                    var registration: ListenerRegistration? = null
                    registration = claim.addSnapshotListener { snap, error ->
                        if (error != null) {
                            if (cont.isActive) cont.resume(Result.Unavailable("Verification unavailable: ${error.code}"))
                            registration?.remove()
                            return@addSnapshotListener
                        }
                        val status = snap?.getString("status") ?: return@addSnapshotListener
                        when (status) {
                            "verified" -> {
                                val expires = snap.getLong("expiresAtMillis") ?: 0L
                                val verified = snap.getLong("verifiedAtMillis") ?: 0L
                                if (cont.isActive) cont.resume(
                                    if (expires > 0L && verified > 0L) Result.Verified(expires, verified)
                                    else Result.Rejected("Server returned an incomplete verdict.")
                                )
                                registration?.remove()
                            }
                            "rejected" -> {
                                if (cont.isActive) cont.resume(Result.Rejected(snap.getString("reason") ?: "Purchase rejected."))
                                registration?.remove()
                            }
                            // "pending" → keep waiting for the function
                        }
                    }
                    cont.invokeOnCancellation { registration?.remove() }

                    claim.set(
                        mapOf(
                            "packageName" to packageName,
                            "productId" to productId,
                            "purchaseToken" to purchaseToken,
                            "status" to "pending",
                            "createdAt" to Timestamp.now()
                        )
                    ).addOnFailureListener { e ->
                        if (cont.isActive) cont.resume(Result.Unavailable("Could not submit purchase for verification: ${e.message}"))
                        registration?.remove()
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "verify failed: ${e.javaClass.simpleName}")   // never log the token
            Result.Unavailable("Verification error.")
        }
        return outcome ?: Result.Unavailable("Verification timed out. Please try again.")
    }

    private companion object { const val TAG = "PlayPurchaseVerifier" }
}
