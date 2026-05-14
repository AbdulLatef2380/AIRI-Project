package com.airi.assistant.execution.prefs

import android.content.Context
import android.content.SharedPreferences
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.PrivacyLevel

/**
 * Persistent user preferences for the Hybrid Execution layer.
 *
 * Stored in a dedicated [SharedPreferences] file so they're isolated from
 * other app preferences and can be cleared independently. All reads are
 * synchronous (in-memory after the first load) — never perform IO on a
 * background thread from this class.
 *
 * Defaults are chosen conservatively:
 *  - [ExecutionMode.HYBRID] — the most capable mode
 *  - [PrivacyLevel.BALANCED] — sanitized cloud prompts, nothing raw uploaded
 *  - [CloudProvider.OPENAI] — most widely configured in existing RemoteModelRegistry
 *  - internet permission = false — user must explicitly enable cloud
 *  - offline fallback = true — always fall back to local when cloud fails
 *  - max cloud tokens per day = 50 000 (soft limit, UI-visible)
 *
 * All setters apply the preference to SharedPreferences synchronously via
 * `commit()` so the value is durable before the setter returns. This is
 * intentional: execution mode changes are safety-critical (LOCAL_ONLY must
 * never silently revert).
 */
class ExecModePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    // ── Execution mode ────────────────────────────────────────────────────────

    var executionMode: ExecutionMode
        get() = prefs.getString(KEY_EXEC_MODE, ExecutionMode.HYBRID.name)
            ?.let { runCatching { ExecutionMode.valueOf(it) }.getOrNull() }
            ?: ExecutionMode.HYBRID
        set(value) {
            prefs.edit().putString(KEY_EXEC_MODE, value.name).apply()
        }

    // ── Privacy level ─────────────────────────────────────────────────────────

    var privacyLevel: PrivacyLevel
        get() = prefs.getString(KEY_PRIVACY_LEVEL, PrivacyLevel.BALANCED.name)
            ?.let { runCatching { PrivacyLevel.valueOf(it) }.getOrNull() }
            ?: PrivacyLevel.BALANCED
        set(value) {
            prefs.edit().putString(KEY_PRIVACY_LEVEL, value.name).apply()
        }

    // ── Preferred cloud provider ──────────────────────────────────────────────

    var preferredProvider: CloudProvider
        get() = prefs.getString(KEY_PROVIDER, CloudProvider.OPENAI.name)
            ?.let { runCatching { CloudProvider.valueOf(it) }.getOrNull() }
            ?: CloudProvider.OPENAI
        set(value) {
            prefs.edit().putString(KEY_PROVIDER, value.name).apply()
        }

    // ── Network permission ────────────────────────────────────────────────────

    /**
     * Explicit user grant for AIRI to make internet requests for AI inference.
     * Must be true for CLOUD_ONLY and HYBRID modes to actually reach the network.
     * Setting to false acts as an additional safety gate on top of [executionMode].
     */
    var internetPermissionGranted: Boolean
        get() = prefs.getBoolean(KEY_INTERNET_PERM, false)
        set(value) {
            prefs.edit().putBoolean(KEY_INTERNET_PERM, value).apply()
        }

    // ── Offline fallback ──────────────────────────────────────────────────────

    /**
     * When true, CLOUD_ONLY mode will fall back to local inference if the
     * cloud call fails or times out. When false, the request fails with an
     * explicit error message so the user knows cloud is unavailable.
     */
    var offlineFallbackEnabled: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_FALLBACK, true)
        set(value) {
            prefs.edit().putBoolean(KEY_OFFLINE_FALLBACK, value).apply()
        }

    // ── Cloud usage cap ───────────────────────────────────────────────────────

    /**
     * Approximate daily cloud token budget. UI shows usage vs. this cap.
     * Router uses this as a soft gate: when exceeded, HYBRID falls back to
     * LOCAL rather than making additional cloud calls.
     * 0 = unlimited.
     */
    var maxDailyCloudTokens: Int
        get() = prefs.getInt(KEY_MAX_CLOUD_TOKENS, 50_000)
        set(value) {
            prefs.edit().putInt(KEY_MAX_CLOUD_TOKENS, value.coerceAtLeast(0)).apply()
        }

    /** Running tally for the current calendar day. Reset on date change. */
    var cloudTokensUsedToday: Int
        get() {
            val dayKey = System.currentTimeMillis() / 86_400_000L
            if (prefs.getLong(KEY_CLOUD_USAGE_DAY, 0L) != dayKey) {
                prefs.edit()
                    .putLong(KEY_CLOUD_USAGE_DAY, dayKey)
                    .putInt(KEY_CLOUD_TOKENS_TODAY, 0)
                    .apply()
                return 0
            }
            return prefs.getInt(KEY_CLOUD_TOKENS_TODAY, 0)
        }
        set(value) {
            val dayKey = System.currentTimeMillis() / 86_400_000L
            prefs.edit()
                .putLong(KEY_CLOUD_USAGE_DAY, dayKey)
                .putInt(KEY_CLOUD_TOKENS_TODAY, value.coerceAtLeast(0))
                .apply()
        }

    /** Add to today's cloud token count. Thread-safe (write on caller's thread). */
    fun recordCloudTokens(count: Int) {
        cloudTokensUsedToday += count
    }

    /** True when the daily cloud budget has been exhausted. */
    val isCloudBudgetExhausted: Boolean
        get() {
            val cap = maxDailyCloudTokens
            return cap > 0 && cloudTokensUsedToday >= cap
        }

    // ── Effective mode (resolved with privacy + permission checks) ────────────

    /**
     * The effective execution mode after applying all safety gates:
     *  - If [PrivacyLevel.MAXIMUM] → always LOCAL_ONLY
     *  - If [internetPermissionGranted] is false → always LOCAL_ONLY
     *  - If daily budget exhausted in CLOUD_ONLY → LOCAL_ONLY (if fallback) or CLOUD_ONLY (error)
     *  - Otherwise → user's chosen [executionMode]
     */
    val effectiveMode: ExecutionMode
        get() {
            if (privacyLevel == PrivacyLevel.MAXIMUM) return ExecutionMode.LOCAL_ONLY
            if (!internetPermissionGranted) return ExecutionMode.LOCAL_ONLY
            if (executionMode == ExecutionMode.CLOUD_ONLY &&
                isCloudBudgetExhausted && offlineFallbackEnabled) return ExecutionMode.LOCAL_ONLY
            return executionMode
        }

    private companion object {
        const val PREFS_FILE           = "airi_exec_prefs"
        const val KEY_EXEC_MODE        = "exec_mode"
        const val KEY_PRIVACY_LEVEL    = "privacy_level"
        const val KEY_PROVIDER         = "preferred_provider"
        const val KEY_INTERNET_PERM    = "internet_permission_granted"
        const val KEY_OFFLINE_FALLBACK = "offline_fallback_enabled"
        const val KEY_MAX_CLOUD_TOKENS = "max_daily_cloud_tokens"
        const val KEY_CLOUD_USAGE_DAY  = "cloud_usage_day"
        const val KEY_CLOUD_TOKENS_TODAY = "cloud_tokens_today"
    }
}
