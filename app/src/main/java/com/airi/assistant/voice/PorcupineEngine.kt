package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import com.airi.assistant.BuildConfig
import java.io.File

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
 *   1. value the user pasted at runtime via Voice Settings (encrypted prefs)
 *   2. [BuildConfig.PICOVOICE_ACCESS_KEY] (set at build time via gradle prop / env)
 *
 * When either of (.ppn) or (AccessKey) is missing the wake-word service
 * refuses to start and the UI explains exactly what's missing.
 */
object PorcupineEngine {

    private const val TAG = "AIRI_VOICE"
    private const val PREFS = "airi_voice_secure"
    private const val KEY_ACCESS_KEY = "picovoice_access_key"

    private const val PPN_NAME = "hey_airi"
    private const val ASSET_PATH = "voice/$PPN_NAME.ppn"

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
     * or null if no .ppn is bundled in either location. Porcupine's
     * Builder needs a real filesystem path, not an asset URI.
     */
    fun resolvePpnFile(context: Context): File? {
        val located = locatePpn(context) ?: return null
        val (file, _) = located
        return file
    }

    fun accessKey(context: Context): String = resolveAccessKey(context).first

    fun setRuntimeAccessKey(context: Context, key: String?) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().also {
            if (key.isNullOrBlank()) it.remove(KEY_ACCESS_KEY)
            else it.putString(KEY_ACCESS_KEY, key.trim())
        }.apply()
    }

    // ── internals ────────────────────────────────────────────────────────

    /** Returns (key, "runtime"|"build") or ("", null) when nothing is set. */
    private fun resolveAccessKey(context: Context): Pair<String, String?> {
        val runtime = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACCESS_KEY, null)
            .orEmpty()
            .trim()
        if (runtime.isNotEmpty()) return runtime to "runtime"
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
