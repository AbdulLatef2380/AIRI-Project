package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import com.airi.assistant.BuildConfig
import com.airi.assistant.auth.SecureStorage
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Helpers used by [HotwordService] to locate the Porcupine .ppn keyword
 * file and the AccessKey, plus a tiny secure store for runtime-supplied
 * keys.
 *
 * Resolution order for the .ppn:
 *   1. `res/raw/hey_airi.ppn`           (looked up reflectively, no compile dep)
 *   2. `assets/voice/hey_airi.ppn`      (extracted to internal storage on first run)
 *
 * Resolution order for the AccessKey:
 *   1. value the user pasted at runtime via Voice Settings (EncryptedSharedPreferences)
 *   2. [BuildConfig.PICOVOICE_ACCESS_KEY] (set at build time via gradle prop / env)
 *
 * When either of (.ppn) or (AccessKey) is missing the wake-word service
 * refuses to start and the UI explains exactly what's missing.
 *
 * P0-7 FIX: AccessKey is now stored in EncryptedSharedPreferences via SecureStorage.
 * Previously it was stored in plain SharedPreferences named "airi_voice_secure".
 * A one-time migration reads any previously stored plaintext key and re-saves it
 * encrypted, then deletes the plaintext copy.
 */
object PorcupineEngine {

    private const val TAG = "AIRI_VOICE"

    // P0-7: legacy plaintext prefs — used only for one-time migration
    private const val LEGACY_PREFS    = "airi_voice_secure"
    private const val LEGACY_KEY      = "picovoice_access_key"

    // Key name used with SecureStorage.saveLlmKey / getLlmKey / clearLlmKey
    private const val SECURE_KEY_NAME = "picovoice"

    private const val PPN_NAME   = "hey_airi"
    private const val ASSET_PATH = "voice/$PPN_NAME.ppn"

    // P0-7: Cache a SecureStorage instance per ApplicationContext.
    // SecureStorage(context) is safe to construct multiple times but
    // allocates a MasterKey + EncryptedSharedPreferences on each call —
    // cache it to avoid repeated crypto initialisation.
    private val secureStorageRef = AtomicReference<SecureStorage?>(null)

    private fun secureStorage(context: Context): SecureStorage {
        val existing = secureStorageRef.get()
        if (existing != null) return existing
        val created = SecureStorage(context.applicationContext)
        secureStorageRef.compareAndSet(null, created)
        return secureStorageRef.get()!!
    }

    data class Status(
        val accessKeyPresent: Boolean,
        val ppnPresent: Boolean,
        val ppnSourceLabel: String?,
        val accessKeySource: String?
    ) {
        val ready: Boolean get() = accessKeyPresent && ppnPresent
    }

    /** Snapshot of what's currently configured — drives the Voice Settings UI. */
    fun status(context: Context): Status {
        maybeRunLegacyMigration(context)
        val (key, keySrc) = resolveAccessKey(context)
        val ppn = locatePpn(context)
        return Status(
            accessKeyPresent = key.isNotBlank(),
            ppnPresent       = ppn != null,
            ppnSourceLabel   = ppn?.second,
            accessKeySource  = if (key.isNotBlank()) keySrc else null
        )
    }

    /**
     * Returns an extracted on-disk path for the Porcupine keyword file,
     * or null if no .ppn is bundled in either location.
     */
    fun resolvePpnFile(context: Context): File? {
        val located = locatePpn(context) ?: return null
        return located.first
    }

    fun accessKey(context: Context): String {
        maybeRunLegacyMigration(context)
        return resolveAccessKey(context).first
    }

    /** P0-7: Saves key to EncryptedSharedPreferences via SecureStorage. */
    fun setRuntimeAccessKey(context: Context, key: String?) {
        val store = secureStorage(context)
        if (key.isNullOrBlank()) {
            store.clearLlmKey(SECURE_KEY_NAME)        // correct method name: clearLlmKey
        } else {
            store.saveLlmKey(SECURE_KEY_NAME, key.trim())
        }
    }

    // ── internals ────────────────────────────────────────────────────────

    /**
     * One-time migration: if a key exists in old plaintext prefs,
     * copy it to EncryptedSharedPreferences and delete the plaintext copy.
     * Safe to call multiple times — no-ops immediately when no legacy key found.
     */
    private fun maybeRunLegacyMigration(context: Context) {
        val legacyPrefs = context.applicationContext
            .getSharedPreferences(LEGACY_PREFS, Context.MODE_PRIVATE)
        val legacyKey = legacyPrefs.getString(LEGACY_KEY, null)?.trim().orEmpty()
        if (legacyKey.isNotEmpty()) {
            Log.i(TAG, "Migrating Picovoice key from plaintext prefs to EncryptedSharedPreferences")
            secureStorage(context).saveLlmKey(SECURE_KEY_NAME, legacyKey)
            legacyPrefs.edit().remove(LEGACY_KEY).apply()
        }
    }

    /** Returns (key, "runtime"|"build") or ("", null) when nothing is set. */
    private fun resolveAccessKey(context: Context): Pair<String, String?> {
        // 1. Runtime key from EncryptedSharedPreferences
        val runtime = secureStorage(context).getLlmKey(SECURE_KEY_NAME).orEmpty().trim()
        if (runtime.isNotEmpty()) return runtime to "runtime"
        // 2. Build-time key from BuildConfig
        val build = BuildConfig.PICOVOICE_ACCESS_KEY.trim()
        if (build.isNotEmpty()) return build to "build"
        return "" to null
    }

    /** Returns (extracted file, human label) or null. */
    private fun locatePpn(context: Context): Pair<File, String>? {
        val app = context.applicationContext
        // 1) res/raw/hey_airi.ppn (preferred)
        val rawId = app.resources.getIdentifier(PPN_NAME, "raw", app.packageName)
        if (rawId != 0) {
            val outFile = File(app.filesDir, "voice/$PPN_NAME.ppn")
            try {
                if (!outFile.exists() || outFile.length() == 0L) {
                    outFile.parentFile?.mkdirs()
                    app.resources.openRawResource(rawId).use { ins ->
                        outFile.outputStream().use { out -> ins.copyTo(out) }
                    }
                }
                return outFile to "res/raw/$PPN_NAME.ppn"
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to materialize res/raw/$PPN_NAME.ppn: ${t.message}")
            }
        }
        // 2) assets/voice/hey_airi.ppn
        return try {
            val list = app.assets.list("voice")?.toList().orEmpty()
            if (!list.contains("$PPN_NAME.ppn")) return null
            val outFile = File(app.filesDir, "voice/$PPN_NAME.ppn")
            if (!outFile.exists() || outFile.length() == 0L) {
                outFile.parentFile?.mkdirs()
                app.assets.open(ASSET_PATH).use { ins ->
                    outFile.outputStream().use { out -> ins.copyTo(out) }
                }
            }
            outFile to "assets/$ASSET_PATH"
        } catch (t: Throwable) {
            Log.d(TAG, "No bundled .ppn found: ${t.message}")
            null
        }
    }
}
