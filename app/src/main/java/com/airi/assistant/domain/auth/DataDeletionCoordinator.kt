package com.airi.assistant.domain.auth

import android.content.Context
import android.util.Log
import androidx.work.WorkManager
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.memory.entity.AuditLogEntity
import com.airi.assistant.memory.repository.AuditRepository
import com.airi.assistant.memory.repository.StorageRepository
import com.airi.assistant.settings.PreferenceCoordinator
import com.airi.assistant.workspace.ArtifactManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * DataDeletionCoordinator — orchestrates the full GDPR account-deletion workflow.
 *
 * ── Why a dedicated coordinator? ──────────────────────────────────────────────
 * Account deletion touches seven distinct data stores across two layers
 * (server-side and local). Without a coordinator:
 *   - UI code (PrivacyDataSettingsScreen) would accumulate deletion knowledge
 *     across every subsystem — a violation of single-responsibility.
 *   - Partial failures would leave the device in an undefined state with no
 *     clear owner for recovery.
 *   - New data stores added in the future require changes only here, not in
 *     every UI surface that can trigger deletion.
 *
 * ── Execution order ───────────────────────────────────────────────────────────
 *
 *   Step 1: STOP_BACKGROUND_WORK
 *     Cancel all WorkManager jobs BEFORE any deletion so no running worker can
 *     re-write data being wiped.
 *
 *   Step 2: FIREBASE_ACCOUNT_DELETION  [terminal on failure]
 *     Server-side token revocation and account removal via Firebase Auth.
 *     If this step fails, execution is aborted and NO local data is touched.
 *     Rationale: the account still exists on the server. Wiping credentials
 *     locally at this point would lock the user out permanently with no
 *     re-authentication path.
 *
 *   Step 3: ROOM_DATA_WIPE
 *     All 9 Room tables wiped in a single atomic Room transaction:
 *       episodic_memory, chat_sessions, message_embedding, context_cache,
 *       usage_stats, behavior_stats, audit_log, workspace_artifact.
 *
 *   Step 4: FILESYSTEM_WIPE
 *     - Artifact files under <filesDir>/workspace/artifacts/ (in-memory map
 *       cleared; disk directory deleted recursively).
 *     - Chat attachment cache under cacheDir/chat_attachments/.
 *     - Multimodal projection model cache cacheDir/mmproj_active.gguf.
 *
 *   Step 5: CREDENTIAL_WIPE
 *     All entries in EncryptedSharedPreferences cleared atomically via
 *     SecureStorage.clearAll(). Covers OAuth tokens (GitHub, Telegram, Google),
 *     LLM provider API keys, device fingerprint, install UUID, and all
 *     integration PATs (Notion, etc.).
 *
 *   Step 6: PREFERENCE_RESET
 *     PreferenceCoordinator.resetAllToDefaults() covers execution mode, voice,
 *     and theme stores. Additionally, every SharedPreferences file in the app's
 *     data directory is deleted — this covers agent learning state, adaptation
 *     state, reinforcement memory, UI memory, snapshot store, onboarding state,
 *     experiment flags, and any future stores not yet registered with
 *     PreferenceCoordinator. Safe to do after account deletion: all stores are
 *     re-initialised to factory defaults on the next cold start.
 *
 *   Step 7: CACHE_WIPE
 *     system cacheDir deleted recursively, then recreated as an empty directory
 *     (the OS expects cacheDir to exist; deletion alone may cause crashes on
 *     some devices).
 *
 *   Step 8: LOCAL_SIGN_OUT
 *     Clears the in-process Firebase credential state. This is done last so
 *     the audit repository remains writable throughout steps 3–7.
 *
 * ── Idempotency ───────────────────────────────────────────────────────────────
 * All steps 3–8 are idempotent: deleteAll() on an empty Room table, and
 * deleteRecursively() on a nonexistent directory, are both safe no-ops.
 * The coordinator can be re-invoked after a PartialSuccess without risk.
 *
 * ── Failure handling ──────────────────────────────────────────────────────────
 * Only Step 2 is terminal. Steps 3–8 are best-effort: each [Throwable] is
 * captured in [DeletionResult.PartialSuccess.failures] and logged to the audit
 * repository, but execution continues so the maximum amount of data is removed
 * even if a single step encounters an unexpected error.
 *
 * ── Architecture constraint ───────────────────────────────────────────────────
 * [StorageRepository] holds no [Context] reference by design. This coordinator
 * owns all Context-dependent operations (WorkManager, filesystem, SharedPrefs
 * enumeration) while repositories remain pure Room facades. No new Context
 * dependency is introduced into any repository.
 */
class DataDeletionCoordinator(
    private val context:               Context,
    private val authService:           AuthService,
    private val storageRepository:     StorageRepository,
    private val artifactManager:       ArtifactManager,
    private val preferenceCoordinator: PreferenceCoordinator,
    private val secureStorage:         SecureStorage,
    private val auditRepository:       AuditRepository
) {

    // ── Public result type ────────────────────────────────────────────────────

    sealed class DeletionResult {

        /** All 8 steps completed without error. */
        object Success : DeletionResult()

        /**
         * Firebase account deletion failed before any local data was touched.
         *
         * The account still exists on the server. Callers should prompt
         * re-authentication if [requiresReauth] is true (Firebase returns a
         * FirebaseAuthRecentLoginRequiredException in this case).
         */
        data class FirebaseAuthFailed(
            val message:       String,
            val requiresReauth: Boolean = false
        ) : DeletionResult()

        /**
         * Firebase deletion succeeded, but one or more local-cleanup steps
         * failed. The account is gone server-side; some local data may remain.
         *
         * Callers should surface this to the user and suggest reinstalling the
         * app or retrying. The coordinator is idempotent — a retry is safe.
         */
        data class PartialSuccess(
            val completedSteps: List<Step>,
            val failures:       List<StepFailure>
        ) : DeletionResult()
    }

    /** Ordered enumeration of each deletion step. */
    enum class Step {
        STOP_BACKGROUND_WORK,
        FIREBASE_ACCOUNT_DELETION,
        ROOM_DATA_WIPE,
        FILESYSTEM_WIPE,
        CREDENTIAL_WIPE,
        PREFERENCE_RESET,
        CACHE_WIPE,
        LOCAL_SIGN_OUT
    }

    /** A record of which step failed and why. */
    data class StepFailure(val step: Step, val cause: Throwable)

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Execute the full account-deletion workflow.
     *
     * Must be called from a coroutine. IO-bound steps dispatch to
     * [Dispatchers.IO] internally; callers may invoke from any dispatcher.
     *
     * @return [DeletionResult] describing the outcome. The caller is
     *   responsible for navigating to the login screen on [DeletionResult.Success]
     *   or [DeletionResult.PartialSuccess], and for prompting the user on
     *   [DeletionResult.FirebaseAuthFailed].
     */
    suspend fun deleteAccount(): DeletionResult {
        val failures  = mutableListOf<StepFailure>()
        val completed = mutableListOf<Step>()

        Log.i(TAG, "AIRI_PROOF GDPR_DELETE_INITIATED")
        auditRepository.log("GDPR", "GDPR_DELETE_INITIATED", AuditLogEntity.Level.WARN)

        // ── Step 1: Stop background workers ──────────────────────────────────
        // Must be first — prevents any running WorkManager worker from writing
        // new data into the stores we are about to wipe.
        runStep(Step.STOP_BACKGROUND_WORK, failures, completed) {
            WorkManager.getInstance(context.applicationContext).cancelAllWork()
        }

        // ── Step 2: Firebase account deletion — TERMINAL ──────────────────────
        // If Firebase fails we return immediately without touching local data.
        val authError = deleteFirebaseAccount()
        if (authError != null) {
            val needsReauth = authError.contains("requires recent", ignoreCase = true) ||
                              authError.contains("RecentLoginRequired",  ignoreCase = true)
            Log.w(TAG, "AIRI_PROOF GDPR_DELETE_FIREBASE_FAILED requiresReauth=$needsReauth reason=$authError")
            auditRepository.error("GDPR",
                "GDPR_DELETE_FIREBASE_FAILED requiresReauth=$needsReauth reason=$authError")
            return DeletionResult.FirebaseAuthFailed(authError, needsReauth)
        }
        completed += Step.FIREBASE_ACCOUNT_DELETION
        Log.i(TAG, "AIRI_PROOF GDPR_DELETE_FIREBASE_SUCCESS")
        auditRepository.log("GDPR", "GDPR_DELETE_FIREBASE_SUCCESS", AuditLogEntity.Level.WARN)

        // ── Steps 3–8: Best-effort local cleanup ──────────────────────────────
        // Each step is independent. A failure in one does not skip the others.

        // Step 3 — Room: all 9 tables in a single atomic transaction.
        runStep(Step.ROOM_DATA_WIPE, failures, completed) {
            storageRepository.deleteAllData()
        }

        // Step 4 — Filesystem:
        //   a) ArtifactManager clears its in-memory map and deletes the entire
        //      on-disk artifact directory. The Room metadata for workspace_artifact
        //      was already wiped in Step 3; this handles the disk layer only.
        //   b) Chat attachment cache written by ChatViewModel image-picker.
        //   c) Active multimodal projection model symlink / copy.
        runStep(Step.FILESYSTEM_WIPE, failures, completed) {
            withContext(Dispatchers.IO) {
                artifactManager.deleteAll()
                File(context.cacheDir, "chat_attachments").deleteRecursively()
                File(context.cacheDir, "mmproj_active.gguf").delete()
            }
        }

        // Step 5 — Credentials: atomically clears the entire
        // EncryptedSharedPreferences store via a single clear().apply() call.
        runStep(Step.CREDENTIAL_WIPE, failures, completed) {
            secureStorage.clearAll()
        }

        // Step 6 — Preferences:
        //   a) Resets execution-mode, voice, and theme stores via the coordinator.
        //   b) Deletes every SharedPreferences file in the app's data directory.
        //      This covers stores not managed by PreferenceCoordinator: agent
        //      learning state, adaptation records, reinforcement memory, UI memory,
        //      execution-graph snapshots, onboarding flags, experiment assignments,
        //      and any future stores not yet registered.
        runStep(Step.PREFERENCE_RESET, failures, completed) {
            preferenceCoordinator.resetAllToDefaults()
            withContext(Dispatchers.IO) {
                val sharedPrefsDir = File(
                    context.filesDir.parent ?: return@withContext,
                    "shared_prefs"
                )
                sharedPrefsDir.listFiles()?.forEach { it.delete() }
            }
        }

        // Step 7 — Cache: wipe the OS cache directory, then recreate it.
        // Some devices / Compose internals expect cacheDir to exist; plain
        // deleteRecursively() without mkdirs() can cause NPEs on cold start.
        runStep(Step.CACHE_WIPE, failures, completed) {
            withContext(Dispatchers.IO) {
                context.cacheDir.deleteRecursively()
                context.cacheDir.mkdirs()
            }
        }

        // Step 8 — Local sign-out: clear in-process Firebase state last so the
        // audit repository stays writable throughout steps 3–7.
        runStep(Step.LOCAL_SIGN_OUT, failures, completed) {
            authService.signOut()
        }

        return if (failures.isEmpty()) {
            Log.i(TAG, "AIRI_PROOF GDPR_DELETE_SUCCESS steps=${completed.size}")
            auditRepository.log("GDPR", "GDPR_DELETE_SUCCESS steps=${completed.size}",
                AuditLogEntity.Level.WARN)
            DeletionResult.Success
        } else {
            val failedNames  = failures.joinToString { it.step.name }
            val successCount = completed.size
            Log.w(TAG, "AIRI_PROOF GDPR_DELETE_PARTIAL succeeded=$successCount failed=${failures.size} steps=$failedNames")
            auditRepository.warn("GDPR",
                "GDPR_DELETE_PARTIAL succeeded=$successCount failed=${failures.size} failedSteps=$failedNames")
            DeletionResult.PartialSuccess(completed, failures)
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Bridge [AuthService.deleteAccount] (callback-based API) to a suspend
     * function via [suspendCancellableCoroutine].
     *
     * @return null on success; the error message string on failure.
     */
    private suspend fun deleteFirebaseAccount(): String? =
        suspendCancellableCoroutine { cont ->
            authService.deleteAccount { success, errorMsg ->
                if (cont.isActive) {
                    cont.resume(
                        if (success) null
                        else errorMsg ?: "Firebase account deletion failed"
                    )
                }
            }
        }

    /**
     * Execute [block] and record the outcome.
     *
     * On success: appends [step] to [completed] and logs at INFO.
     * On failure: appends a [StepFailure] to [failures], logs at ERROR with
     *   stack trace, and writes to the audit repository. Execution continues —
     *   the exception is never re-thrown.
     */
    private suspend fun runStep(
        step:      Step,
        failures:  MutableList<StepFailure>,
        completed: MutableList<Step>,
        block:     suspend () -> Unit
    ) {
        Log.d(TAG, "GDPR_STEP_START step=${step.name}")
        runCatching { block() }
            .onSuccess {
                completed += step
                Log.i(TAG, "GDPR_STEP_DONE step=${step.name}")
            }
            .onFailure { t ->
                failures += StepFailure(step, t)
                Log.e(TAG, "GDPR_STEP_FAILED step=${step.name} reason=${t.message}", t)
                runCatching {
                    auditRepository.error("GDPR",
                        "GDPR_STEP_FAILED step=${step.name} reason=${t.javaClass.simpleName}: ${t.message}")
                }
            }
    }

    private companion object {
        const val TAG = "AIRI_DataDeletion"
    }
}
