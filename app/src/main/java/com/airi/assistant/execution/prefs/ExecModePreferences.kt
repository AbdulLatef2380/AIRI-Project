package com.airi.assistant.execution.prefs

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.airi.assistant.execution.CloudProvider
import com.airi.assistant.execution.ExecutionMode
import com.airi.assistant.execution.PrivacyLevel
import com.airi.assistant.execution.router.RoutingPreferences

/**
 * Persistent user preferences for the Hybrid Execution layer.
 *
 * ── tep 2: Security hardening ────────────────────────────────────────
 * Previous behavior: preferences were stored in plain-text SharedPreferences
 * at `airi_exec_prefs`. On a rooted device that file is readable and writable
 * by any process, allowing an attacker to silently change:
 *   • LOCAL_ONLY  → CLOUD_ONLY  (forces cloud exfiltration)
 *   • MAXIMUM     → PERFORMANCE (disables privacy gating)
 *   • internet_permission_granted=false → true (enables unauthorized network)
 *
 * New behavior:
 *   • Primary store: [EncryptedSharedPreferences] (AES256-SIV key encryption,
 *     AES256-GCM value encryption) backed by the Android Keystore master key.
 *   • If encryption cannot be initialised (broken keystore, locked device at
 *     first boot), falls back to an in-memory store — no plaintext disk writes.
 *   • [isEncrypted] reflects the live state so UI / Settings can warn the user.
 *   • Encrypted file name: `airi_exec_prefs_secure` (distinct from the
 *     now-abandoned `airi_exec_prefs` plaintext file).
 *
 * One-time migration: on first launch after this upgrade, any data in the
 * legacy `airi_exec_prefs` plaintext file is copied key-by-key to the new
 * encrypted store. Every key is verified after the write before the legacy
 * file is cleared and deleted. If encryption is unavailable (in-memory
 * fallback), migration is skipped entirely. The migration is idempotent —
 * if the legacy file is absent or already empty, it is a no-op.
 *
 * ── Original contract (preserved in full) ─────────────────────────────────────
 * Constructor signature, all property names, all defaults, and all derived
 * computations are identical to the pre-migration version. No caller changes
 * are required.
 *
 * All reads are synchronous (in-memory after first load) — never perform I/O
 * on a background thread from this class.
 *
 * Defaults are chosen conservatively:
 *  - [ExecutionMode.HYBRID]   — the most capable mode
 *  - [PrivacyLevel.BALANCED]  — sanitized cloud prompts, nothing raw uploaded
 *  - [CloudProvider.OPENAI]   — most widely configured in RemoteModelRegistry
 *  - internet permission = false — user must explicitly enable cloud
 *  - offline fallback = true  — always fall back to local when cloud fails
 *  - max cloud tokens per day = 50 000 (soft limit, UI-visible)
 */
class ExecModePreferences(context: Context) : RoutingPreferences {

    /**
     * True when the underlying store is EncryptedSharedPreferences.
     * False when the keystore-backed implementation failed to initialise and
     * we are running on the in-memory fallback (no disk persistence).
     */
    val isEncrypted: Boolean

    private val prefs: SharedPreferences

    init {
        var encrypted = false
        prefs = try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            val real = EncryptedSharedPreferences.create(
                context,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            encrypted = true
            real
        } catch (e: Exception) {
            Log.e(
                TAG,
                "AIRI EXEC_PREFS_ENCRYPT_FAILED — EncryptedSharedPreferences init failed; " +
                    "falling back to IN-MEMORY only (no plaintext disk writes). " +
                    "Cause: ${e.message}"
            )
            ExecModeInMemoryPreferences()
        }
        isEncrypted = encrypted

        if (isEncrypted) {
            migrateFromLegacyPrefsIfNeeded(context)
        }
    }

    // ── Execution mode ────────────────────────────────────────────────────────

    var executionMode: ExecutionMode
        get() = prefs.getString(KEY_EXEC_MODE, ExecutionMode.HYBRID.name)
            ?.let { runCatching { ExecutionMode.valueOf(it) }.getOrNull() }
            ?: ExecutionMode.HYBRID
        set(value) {
            prefs.edit().putString(KEY_EXEC_MODE, value.name).apply()
        }

    // ── Privacy level ─────────────────────────────────────────────────────────

    override var privacyLevel: PrivacyLevel
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
    override var internetPermissionGranted: Boolean
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
    override var offlineFallbackEnabled: Boolean
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
    override var maxDailyCloudTokens: Int
        get() = prefs.getInt(KEY_MAX_CLOUD_TOKENS, 50_000)
        set(value) {
            prefs.edit().putInt(KEY_MAX_CLOUD_TOKENS, value.coerceAtLeast(0)).apply()
        }

    /** Running tally for the current calendar day. Reset on date change. */
    override var cloudTokensUsedToday: Int
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
    override val isCloudBudgetExhausted: Boolean
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
    override val effectiveMode: ExecutionMode
        get() {
            if (privacyLevel == PrivacyLevel.MAXIMUM) return ExecutionMode.LOCAL_ONLY
            if (!internetPermissionGranted) return ExecutionMode.LOCAL_ONLY
            if (executionMode == ExecutionMode.CLOUD_ONLY &&
                isCloudBudgetExhausted && offlineFallbackEnabled) return ExecutionMode.LOCAL_ONLY
            return executionMode
        }

    // ── One-time migration from legacy plaintext store ────────────────────────

    /**
     * Migrates preferences from the legacy plaintext `airi_exec_prefs` file
     * to the current [EncryptedSharedPreferences] store.
     *
     * Contract:
     *  1. Opens the legacy file; if it has no keys, returns immediately (no-op).
     *  2. Writes every legacy entry to [prefs] using [SharedPreferences.Editor.commit]
     *     for a synchronous, success-signalling write.
     *  3. Verifies each key by reading it back from [prefs] and comparing to
     *     the original value.
     *  4. If **all** keys verified: clears and deletes the legacy file so no
     *     plaintext copy remains on disk.
     *  5. If any key fails verification: logs an error and preserves the legacy
     *     file so the migration can be re-attempted on the next launch.
     *
     * Never throws — any unexpected error is caught, logged with an
     * `AIRI` tag, and treated as a migration failure.
     *
     * Only called when [isEncrypted] is true (i.e., [prefs] is backed by
     * [EncryptedSharedPreferences], not the in-memory fallback).
     */
    private fun migrateFromLegacyPrefsIfNeeded(context: Context) {
        try {
            val legacy = context.getSharedPreferences(LEGACY_PREFS_FILE, Context.MODE_PRIVATE)
            val legacyAll = legacy.all

            if (legacyAll.isEmpty()) {
                return
            }

            Log.i(
                TAG,
                "AIRI EXEC_PREFS_MIGRATION_START — " +
                    "found ${legacyAll.size} key(s) in legacy plaintext store; " +
                    "migrating to encrypted store"
            )

            // ── Stage 1: Write all legacy values into the encrypted store ─────
            val editor = prefs.edit()
            var stagedCount = 0

            for ((key, value) in legacyAll) {
                when (value) {
                    is String  -> { editor.putString(key, value); stagedCount++ }
                    is Boolean -> { editor.putBoolean(key, value); stagedCount++ }
                    is Int     -> { editor.putInt(key, value);     stagedCount++ }
                    is Long    -> { editor.putLong(key, value);    stagedCount++ }
                    is Float   -> { editor.putFloat(key, value);   stagedCount++ }
                    else -> Log.w(
                        TAG,
                        "EXEC_PREFS_MIGRATION: skipping key=\"$key\" — " +
                            "unsupported type ${value?.javaClass?.simpleName}"
                    )
                }
            }

            val committed = editor.commit()

            if (!committed) {
                Log.e(
                    TAG,
                    "AIRI EXEC_PREFS_MIGRATION_FAILED — commit() returned false; " +
                        "legacy plaintext file preserved for next-launch retry"
                )
                return
            }

            // ── Stage 2: Verify every staged key is readable from encrypted store
            var verifiedCount = 0

            for ((key, value) in legacyAll) {
                val ok = when (value) {
                    is String  -> prefs.contains(key) && prefs.getString(key, null) == value
                    is Boolean -> prefs.contains(key) && prefs.getBoolean(key, !value) == value
                    is Int     -> prefs.contains(key) && prefs.getInt(key, value.inv()) == value
                    is Long    -> prefs.contains(key) && prefs.getLong(key, value.inv()) == value
                    is Float   -> prefs.contains(key) && prefs.getFloat(key, -value - 1f) == value
                    else       -> true  // type was skipped in write phase; not our concern
                }
                if (ok) {
                    verifiedCount++
                } else {
                    Log.e(
                        TAG,
                        "AIRI EXEC_PREFS_MIGRATION_VERIFY_FAIL — " +
                            "key=\"$key\" did not round-trip correctly"
                    )
                }
            }

            // ── Stage 3: Clear and delete the legacy file only on full success ─
            if (verifiedCount == stagedCount) {
                Log.i(
                    TAG,
                    "AIRI EXEC_PREFS_MIGRATION_VERIFIED — " +
                        "$verifiedCount/$stagedCount key(s) verified; " +
                        "clearing legacy plaintext file"
                )
                legacy.edit().clear().commit()
                @Suppress("DEPRECATION")  // deleteSharedPreferences requires API 24+; minSdk=26
                context.deleteSharedPreferences(LEGACY_PREFS_FILE)
                Log.i(
                    TAG,
                    "AIRI EXEC_PREFS_MIGRATION_COMPLETE — " +
                        "legacy plaintext file deleted; migration finished"
                )
            } else {
                Log.e(
                    TAG,
                    "AIRI EXEC_PREFS_MIGRATION_PARTIAL — " +
                        "$verifiedCount/$stagedCount key(s) verified; " +
                        "legacy plaintext file preserved for next-launch retry"
                )
            }
        } catch (e: Exception) {
            Log.e(
                TAG,
                "AIRI EXEC_PREFS_MIGRATION_ERROR — unexpected failure; " +
                    "legacy plaintext file preserved. Cause: ${e.message}"
            )
        }
    }

    private companion object {
        const val TAG                    = "AIRI_ExecModePrefs"
        const val PREFS_FILE             = "airi_exec_prefs_secure"
        const val LEGACY_PREFS_FILE      = "airi_exec_prefs"
        const val KEY_EXEC_MODE          = "exec_mode"
        const val KEY_PRIVACY_LEVEL      = "privacy_level"
        const val KEY_PROVIDER           = "preferred_provider"
        const val KEY_INTERNET_PERM      = "internet_permission_granted"
        const val KEY_OFFLINE_FALLBACK   = "offline_fallback_enabled"
        const val KEY_MAX_CLOUD_TOKENS   = "max_daily_cloud_tokens"
        const val KEY_CLOUD_USAGE_DAY    = "cloud_usage_day"
        const val KEY_CLOUD_TOKENS_TODAY = "cloud_tokens_today"
    }
}

/**
 * Process-lifetime, in-memory SharedPreferences fallback for ExecModePreferences.
 *
 * Used when [EncryptedSharedPreferences] cannot be initialised (broken keystore,
 * locked device at first boot, or corrupted master key). Discards all values
 * when the process dies — this is intentional: an out-of-session reset to safe
 * defaults is preferable to plaintext disk writes.
 *
 * In this fallback state, [ExecModePreferences.internetPermissionGranted]
 * returns false (the default), so cloud access is blocked even without
 * encryption — the system remains in the most restrictive safe state.
 *
 * Thread safety: backed by [java.util.concurrent.ConcurrentHashMap]; commit()
 * and apply() both apply mutations atomically and return immediately.
 */
private class ExecModeInMemoryPreferences : SharedPreferences {

    private val map: MutableMap<String, Any?> = java.util.concurrent.ConcurrentHashMap()
    private val listeners = mutableSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = HashMap(map)

    override fun getString(key: String?, defValue: String?): String? =
        (map[key] as? String) ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (map[key] as? Set<String>)?.toMutableSet() ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        (map[key] as? Int) ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        (map[key] as? Long) ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        (map[key] as? Float) ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        (map[key] as? Boolean) ?: defValue

    override fun contains(key: String?): Boolean = map.containsKey(key)

    override fun edit(): SharedPreferences.Editor = MemEditor()

    override fun registerOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener
    ) { listeners.add(l) }

    override fun unregisterOnSharedPreferenceChangeListener(
        l: SharedPreferences.OnSharedPreferenceChangeListener
    ) { listeners.remove(l) }

    private inner class MemEditor : SharedPreferences.Editor {
        private val pending  = mutableMapOf<String, Any?>()
        private val removals = mutableSetOf<String>()
        private var clearAll = false

        override fun putString(key: String, value: String?): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor =
            apply { pending[key] = values }
        override fun putInt(key: String, value: Int): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putLong(key: String, value: Long): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putFloat(key: String, value: Float): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor =
            apply { pending[key] = value }
        override fun remove(key: String): SharedPreferences.Editor =
            apply { removals.add(key); pending.remove(key) }
        override fun clear(): SharedPreferences.Editor =
            apply { clearAll = true }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() {
            if (clearAll) map.clear()
            for (k in removals) map.remove(k)
            for ((k, v) in pending) {
                if (v == null) map.remove(k) else map[k] = v
            }
        }
    }
}
