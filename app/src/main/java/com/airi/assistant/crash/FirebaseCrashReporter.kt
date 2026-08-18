package com.airi.assistant.crash

import android.util.Log
import com.google.firebase.crashlytics.FirebaseCrashlytics

/**
 * FirebaseCrashReporter — production crash and non-fatal error reporting.
 *
 * ── PURPOSE ───────────────────────────────────────────────────────────────────
 * Wraps FirebaseCrashlytics so that the rest of the codebase never imports
 * Firebase directly. All methods are null-safe: if Crashlytics fails to
 * initialise (e.g. no google-services.json in a fork) the calls silently no-op
 * and log to Logcat instead.
 *
 * ── CONSENT GATE ──────────────────────────────────────────────────────────────
 * FirebaseCrashlytics auto-collection is DISABLED in AndroidManifest.xml
 * (firebase_crashlytics_collection_enabled = false). Collection is enabled at
 * runtime only after the user grants telemetry consent in OnboardingScreen /
 * SettingsScreen. Call [enableCollection] after consent is confirmed.
 *
 * ── KEY ENRICHMENT ────────────────────────────────────────────────────────────
 * Session metadata (model, execution mode, UCL version) is set via [setKey] so
 * every crash report arrives pre-enriched, reducing triage time from minutes
 * to seconds.
 *
 * ── THREAD SAFETY ─────────────────────────────────────────────────────────────
 * FirebaseCrashlytics is thread-safe. All methods here are safe to call from
 * any thread or coroutine dispatcher.
 */
object FirebaseCrashReporter {

    private const val TAG = "FirebaseCrashReporter"

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Enable crash data collection. Call this ONLY after the user has granted
     * telemetry consent — never before.
     */
    fun enableCollection() {
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(true)
            Log.i(TAG, "AIRI CRASHLYTICS_ENABLED")
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics enableCollection failed: ${e.message}")
        }
    }

    /**
     * Disable crash data collection (called when user revokes consent).
     */
    fun disableCollection() {
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(false)
            Log.i(TAG, "AIRI CRASHLYTICS_DISABLED")
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics disableCollection failed: ${e.message}")
        }
    }

    // ── Identity & enrichment ─────────────────────────────────────────────────

    /**
     * Set an anonymous user identifier for crash grouping.
     * MUST be an opaque UID — never PII (name, email, phone).
     */
    fun setUserId(uid: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().setUserId(uid)
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics setUserId failed: ${e.message}")
        }
    }

    /**
     * Attach a custom key–value pair to every subsequent crash report.
     *
     * Recommended keys:
     *   "model_name"      — active LLM model file name
     *   "exec_mode"       — LOCAL_ONLY | CLOUD | HYBRID
     *   "ucl_version"     — UCL build version
     *   "onboarding_done" — true/false
     *   "session_count"   — integer session counter
     */
    fun setKey(key: String, value: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics setKey failed: ${e.message}")
        }
    }

    fun setKey(key: String, value: Boolean) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics setKey failed: ${e.message}")
        }
    }

    fun setKey(key: String, value: Int) {
        runCatching {
            FirebaseCrashlytics.getInstance().setCustomKey(key, value)
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics setKey failed: ${e.message}")
        }
    }

    // ── Logging & error capture ───────────────────────────────────────────────

    /**
     * Append a breadcrumb log line. These appear in the "Logs" tab of a crash
     * report and are invaluable for reconstructing event sequences.
     *
     * Limited to 64 KB total per session by the Crashlytics SDK; older lines
     * are discarded automatically.
     */
    fun log(message: String) {
        runCatching {
            FirebaseCrashlytics.getInstance().log(message)
        }.onFailure { /* silently ignore — never crash the crash reporter */ }
    }

    /**
     * Record a non-fatal exception. Use this for recoverable errors that should
     * be surfaced in the Crashlytics dashboard without causing an ANR or crash:
     *   - UCL graph aborts
     *   - LLM inference timeouts
     *   - Network failures in cloud mode
     *   - Unexpected StateFlow emissions
     */
    fun recordNonFatal(throwable: Throwable) {
        runCatching {
            FirebaseCrashlytics.getInstance().recordException(throwable)
            Log.w(TAG, "AIRI NON_FATAL_RECORDED class=${throwable::class.simpleName}")
        }.onFailure { e ->
            Log.w(TAG, "Crashlytics recordException failed: ${e.message}")
        }
    }

    /**
     * Record a non-fatal event described by a message string (wraps in a
     * synthetic RuntimeException so it appears as a distinct issue in Crashlytics).
     */
    fun recordNonFatal(message: String, cause: Throwable? = null) {
        val throwable = if (cause != null)
            RuntimeException(message, cause)
        else
            RuntimeException(message)
        recordNonFatal(throwable)
    }
}
