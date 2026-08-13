package com.airi.assistant.domain.auth

import android.app.Activity
import android.util.Log
import com.airi.assistant.domain.error.AppError
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.event.AppEvent
import com.airi.assistant.domain.event.EventBus
import com.airi.assistant.memory.entity.AuditLogEntity
import com.airi.assistant.memory.repository.AuditRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseUser
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.OAuthProvider

/**
 * AuthService — canonical authentication facade.
 *
 * ── Phase 2, Task 3: AuthService Enforcement ──────────────────────────────────
 * All authentication operations (sign-in, OAuth, sign-out, account deletion)
 * now route through this class. UI layers must never call
 * [FirebaseAuth.getInstance()] directly; instead they depend on this service
 * via DI from [com.airi.assistant.core.ServiceLocator].
 *
 * This abstraction decouples the application from the Firebase vendor:
 * swapping to a different auth provider requires changes only here, not
 * scattered across UI screens.
 *
 * ── Audit ─────────────────────────────────────────────────────────────────────
 * Every authentication event emits an [AppEvent] to [EventBus], logs an
 * AIRI_RUNTIME line to logcat, AND writes to [AuditRepository] so the persistent
 * audit trail (Room DB) captures every session boundary.
 *
 * ── Phase 2, Task 10: AuditRepository Integration ─────────────────────────────
 * [auditRepository] is injected from [com.airi.assistant.core.ServiceLocator].
 * The default is null so AuthService remains constructable without a DB context
 * (e.g., in unit tests). When null, only the logcat AIRI_RUNTIME line is written.
 */
class AuthService(
    private val auditRepository: AuditRepository? = null
) {

    private val firebaseAuth: FirebaseAuth = FirebaseAuth.getInstance()

    // ── Email / Password ──────────────────────────────────────────────────────

    fun signIn(email: String, password: String, onResult: (String?) -> Unit) {
        firebaseAuth.signInWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = firebaseAuth.currentUser?.uid ?: "unknown"
                    Log.i(TAG, "AIRI_RUNTIME AUTH_SIGN_IN provider=email uid=$uid")
                    auditRepository?.info("AUTH", "AUTH_SIGN_IN provider=email uid=$uid")
                    EventBus.emitSync(AppEvent.UserSignedIn(uid, "email"))
                    onResult(null)
                } else {
                    val error = AppError.AuthenticationFailed(
                        task.exception?.localizedMessage ?: "Sign in failed",
                        task.exception
                    )
                    AppErrorHandler.log(error)
                    EventBus.emitSync(AppEvent.AuthFailed(error.message))
                    Log.w(TAG, "AIRI_RUNTIME AUTH_SIGN_IN_FAILED provider=email reason=${error.message}")
                    auditRepository?.warn("AUTH", "AUTH_SIGN_IN_FAILED provider=email reason=${error.message}")
                    onResult(AppErrorHandler.toUserMessage(error))
                }
            }
    }

    fun createAccount(email: String, password: String, onResult: (String?) -> Unit) {
        firebaseAuth.createUserWithEmailAndPassword(email, password)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = firebaseAuth.currentUser?.uid ?: "unknown"
                    Log.i(TAG, "AIRI_RUNTIME AUTH_CREATE_ACCOUNT provider=email uid=$uid")
                    auditRepository?.info("AUTH", "AUTH_CREATE_ACCOUNT provider=email uid=$uid")
                    EventBus.emitSync(AppEvent.UserSignedIn(uid, "email_create"))
                    onResult(null)
                } else {
                    val error = AppError.AuthenticationFailed(
                        task.exception?.localizedMessage ?: "Account creation failed",
                        task.exception
                    )
                    AppErrorHandler.log(error)
                    EventBus.emitSync(AppEvent.AuthFailed(error.message))
                    Log.w(TAG, "AIRI_RUNTIME AUTH_CREATE_ACCOUNT_FAILED provider=email reason=${error.message}")
                    auditRepository?.warn("AUTH", "AUTH_CREATE_ACCOUNT_FAILED provider=email reason=${error.message}")
                    onResult(AppErrorHandler.toUserMessage(error))
                }
            }
    }

    // ── OAuth: Google ─────────────────────────────────────────────────────────

    /**
     * Exchange a Google ID-token (obtained by the UI launcher) for a Firebase
     * credential and complete the sign-in. The UI is responsible for launching
     * the Google Sign-In intent and obtaining the token; this method owns the
     * Firebase credential exchange and session creation.
     *
     * @param idToken  Google ID token from [GoogleSignIn.getSignedInAccountFromIntent].
     * @param onResult Null on success; user-facing error message on failure.
     */
    fun signInWithGoogleCredential(idToken: String, onResult: (String?) -> Unit) {
        val credential = GoogleAuthProvider.getCredential(idToken, null)
        firebaseAuth.signInWithCredential(credential)
            .addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    val uid = firebaseAuth.currentUser?.uid ?: "unknown"
                    Log.i(TAG, "AIRI_RUNTIME AUTH_SIGN_IN provider=google uid=$uid")
                    auditRepository?.info("AUTH", "AUTH_SIGN_IN provider=google uid=$uid")
                    EventBus.emitSync(AppEvent.UserSignedIn(uid, "google"))
                    onResult(null)
                } else {
                    val msg = task.exception?.localizedMessage ?: "Google sign-in failed"
                    Log.w(TAG, "AIRI_RUNTIME AUTH_SIGN_IN_FAILED provider=google reason=$msg")
                    auditRepository?.warn("AUTH", "AUTH_SIGN_IN_FAILED provider=google reason=$msg")
                    EventBus.emitSync(AppEvent.AuthFailed(msg))
                    onResult(msg)
                }
            }
    }

    // ── OAuth: GitHub ─────────────────────────────────────────────────────────

    /**
     * Launch the GitHub OAuth flow via Firebase's activity-based sign-in.
     * Requires a non-null [Activity] as the flow opens a Chrome Custom Tab.
     *
     * @param activity   Calling Activity (needed by Firebase OAuth).
     * @param onSuccess  Invoked on successful sign-in.
     * @param onFailure  Invoked with a user-facing error message on failure.
     */
    fun signInWithGitHub(
        activity:  Activity,
        onSuccess: () -> Unit,
        onFailure: (String) -> Unit
    ) {
        val provider = OAuthProvider.newBuilder("github.com")
        firebaseAuth.startActivityForSignInWithProvider(activity, provider.build())
            .addOnSuccessListener {
                val uid = firebaseAuth.currentUser?.uid ?: "unknown"
                Log.i(TAG, "AIRI_RUNTIME AUTH_SIGN_IN provider=github uid=$uid")
                auditRepository?.info("AUTH", "AUTH_SIGN_IN provider=github uid=$uid")
                EventBus.emitSync(AppEvent.UserSignedIn(uid, "github"))
                onSuccess()
            }
            .addOnFailureListener { e ->
                val msg = when {
                    e.message?.contains("cancelled", ignoreCase = true) == true ->
                        "GitHub sign-in cancelled"
                    e.message?.contains("network", ignoreCase = true) == true ->
                        "Network error. Check your connection."
                    else -> "GitHub sign-in failed: ${e.localizedMessage}"
                }
                Log.w(TAG, "AIRI_RUNTIME AUTH_SIGN_IN_FAILED provider=github reason=${e.message}")
                auditRepository?.warn("AUTH", "AUTH_SIGN_IN_FAILED provider=github reason=${e.message}")
                EventBus.emitSync(AppEvent.AuthFailed(msg))
                onFailure(msg)
            }
    }

    // ── Sign-Out ──────────────────────────────────────────────────────────────

    fun signOut() {
        val uid = firebaseAuth.currentUser?.uid ?: "unknown"
        Log.i(TAG, "AIRI_RUNTIME AUTH_SIGN_OUT uid=$uid")
        auditRepository?.info("AUTH", "AUTH_SIGN_OUT uid=$uid")
        EventBus.emitSync(AppEvent.UserSignedOut())
        firebaseAuth.signOut()
    }

    // ── GDPR Account Deletion (Task 4) ────────────────────────────────────────

    /**
     * Permanently delete the current user's account.
     *
     * ── What this does ────────────────────────────────────────────────────────
     * 1. Forces a server-round-trip token refresh so Firebase validates the
     *    session is still active before accepting the deletion.
     * 2. Calls [FirebaseAuth.currentUser.delete] which:
     *    - Revokes all server-side refresh tokens.
     *    - Removes the account from Firebase Authentication.
     *    - Is irreversible without admin SDK intervention.
     * 3. Calls [signOut] locally to clear the in-process credential state.
     *
     * ── Re-authentication ──────────────────────────────────────────────────────
     * Firebase requires a recent sign-in for account deletion. If more than
     * a short window has passed, [delete] will fail with
     * [com.google.firebase.auth.FirebaseAuthRecentLoginRequiredException].
     * Callers should surface this to the user and prompt re-authentication.
     *
     * @param onComplete Receives (success: Boolean, errorMessage: String?).
     *                   [errorMessage] is null on success.
     */
    fun deleteAccount(onComplete: (Boolean, String?) -> Unit) {
        val user = firebaseAuth.currentUser
        if (user == null) {
            Log.w(TAG, "AIRI_RUNTIME AUTH_DELETE_ACCOUNT_FAIL reason=no_current_user")
            auditRepository?.warn("AUTH", "AUTH_DELETE_ACCOUNT_FAIL reason=no_current_user")
            onComplete(false, "No signed-in account to delete.")
            return
        }

        val uid = user.uid
        Log.i(TAG, "AIRI_RUNTIME AUTH_DELETE_ACCOUNT_INITIATED uid=$uid")
        auditRepository?.log("AUTH", "AUTH_DELETE_ACCOUNT_INITIATED uid=$uid", AuditLogEntity.Level.WARN)

        // Force a token refresh to validate session freshness before deletion.
        user.getIdToken(/* forceRefresh = */ true)
            .addOnCompleteListener { tokenTask ->
                if (!tokenTask.isSuccessful) {
                    Log.w(TAG,
                        "AIRI_RUNTIME AUTH_DELETE_ACCOUNT_TOKEN_REFRESH_FAILED uid=$uid " +
                        "reason=${tokenTask.exception?.message}"
                    )
                    auditRepository?.warn("AUTH",
                        "AUTH_DELETE_ACCOUNT_TOKEN_REFRESH_FAILED uid=$uid " +
                        "reason=${tokenTask.exception?.message}")
                    // Proceed anyway — Firebase's delete() performs its own validation.
                }

                user.delete()
                    .addOnCompleteListener { deleteTask ->
                        if (deleteTask.isSuccessful) {
                            Log.i(TAG, "AIRI_RUNTIME AUTH_DELETE_ACCOUNT_SUCCESS uid=$uid")
                            auditRepository?.log("AUTH",
                                "AUTH_DELETE_ACCOUNT_SUCCESS uid=$uid",
                                AuditLogEntity.Level.WARN)
                            EventBus.emitSync(AppEvent.UserSignedOut())
                            // Clear local session state after server-side deletion.
                            runCatching { firebaseAuth.signOut() }
                            onComplete(true, null)
                        } else {
                            val msg = deleteTask.exception?.localizedMessage
                                ?: "Account deletion failed"
                            Log.e(TAG,
                                "AIRI_RUNTIME AUTH_DELETE_ACCOUNT_FAILED uid=$uid reason=$msg"
                            )
                            auditRepository?.error("AUTH",
                                "AUTH_DELETE_ACCOUNT_FAILED uid=$uid reason=$msg")
                            onComplete(false, msg)
                        }
                    }
            }
    }

    // ── State queries ─────────────────────────────────────────────────────────

    fun isSignedIn(): Boolean = firebaseAuth.currentUser != null

    fun currentUser(): FirebaseUser? = firebaseAuth.currentUser

    fun isEmailVerified(): Boolean = firebaseAuth.currentUser?.isEmailVerified == true

    private companion object {
        const val TAG = "AIRI_AuthService"
    }
}
