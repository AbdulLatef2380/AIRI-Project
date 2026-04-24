package com.airi.assistant.voice

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.vosk.Model
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Owns the on-disk catalogue of Vosk speech-recognition models and the
 * full lifecycle of downloading, verifying, extracting and loading them.
 *
 * Models live under: `<filesDir>/vosk_models/<modelId>/...` (a Vosk model
 * is a directory containing files like `am/`, `conf/`, `graph/`, `ivector/`).
 *
 * Active selection is persisted in a tiny SharedPreferences file so the
 * choice survives process death.
 *
 * **Strict offline guarantee:** the only network use in this class is the
 * one-shot download of the model zip you explicitly trigger. Nothing else
 * touches the network — the Vosk recognizer itself runs 100 % on-device.
 */
object VoskModelManager {

    private const val TAG = "AIRI_VOSK"
    private const val PREFS = "vosk_model_prefs"
    private const val KEY_ACTIVE = "active_model_id"
    private const val ROOT_DIR = "vosk_models"

    /** A canned set of well-known Vosk models the user can pick from. */
    data class Preset(
        val id: String,
        val displayName: String,
        val locale: String,
        val sizeMb: Int,
        val url: String,
        /** Optional SHA-256 of the *zip* (lowercase hex). null = skip verify. */
        val sha256: String?
    )

    /** A model that has already been downloaded + extracted into [dir]. */
    data class Installed(
        val id: String,
        val displayName: String,
        val locale: String,
        val dir: File,
        val sizeBytes: Long
    )

    /**
     * Curated list. URLs are pulled from https://alphacephei.com/vosk/models/.
     * The user can also paste a custom URL via the Voice Settings screen.
     *
     * The small EN model SHA-256 is pinned (well-known stable artifact);
     * larger models change occasionally and are accepted unverified — the
     * UI clearly says so before downloading.
     */
    val PRESETS: List<Preset> = listOf(
        Preset(
            id          = "vosk-model-small-en-us-0.15",
            displayName = "English (small, ~40 MB)",
            locale      = "en-US",
            sizeMb      = 40,
            url         = "https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip",
            sha256      = "30f26242c4eb449f948e42cb302dd7a686cb29a3423a8367f99ff41780942498"
        ),
        Preset(
            id          = "vosk-model-en-us-0.22",
            displayName = "English (large, ~1.8 GB)",
            locale      = "en-US",
            sizeMb      = 1800,
            url         = "https://alphacephei.com/vosk/models/vosk-model-en-us-0.22.zip",
            sha256      = null
        ),
        Preset(
            id          = "vosk-model-ar-mgb2-0.4",
            displayName = "Arabic — MGB2 (~1.3 GB)",
            locale      = "ar",
            sizeMb      = 1300,
            url         = "https://alphacephei.com/vosk/models/vosk-model-ar-mgb2-0.4.zip",
            sha256      = null
        )
    )

    private val _activeModelId = MutableStateFlow<String?>(null)
    val activeModelId: StateFlow<String?> = _activeModelId.asStateFlow()

    private val _installed = MutableStateFlow<List<Installed>>(emptyList())
    val installed: StateFlow<List<Installed>> = _installed.asStateFlow()

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.MINUTES)
        .callTimeout(60, TimeUnit.MINUTES)
        .build()

    fun init(context: Context) {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _activeModelId.value = prefs.getString(KEY_ACTIVE, null)
        refreshInstalled(context)
    }

    fun refreshInstalled(context: Context) {
        val root = rootDir(context)
        if (!root.exists()) {
            _installed.value = emptyList()
            return
        }
        val list = root.listFiles().orEmpty()
            .filter { it.isDirectory && isValidVoskModel(it) }
            .map { dir ->
                val preset = PRESETS.firstOrNull { it.id == dir.name }
                Installed(
                    id          = dir.name,
                    displayName = preset?.displayName ?: dir.name,
                    locale      = preset?.locale ?: "—",
                    dir         = dir,
                    sizeBytes   = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                )
            }
        _installed.value = list
        // If the active model has been deleted, clear the selection.
        val active = _activeModelId.value
        if (active != null && list.none { it.id == active }) {
            setActive(context, null)
        } else if (active == null && list.size == 1) {
            // Convenience: if there's exactly one installed model and none
            // is selected, auto-select it.
            setActive(context, list.first().id)
        }
    }

    fun isReady(context: Context): Boolean {
        val active = _activeModelId.value ?: return false
        val installed = _installed.value
        if (installed.isEmpty()) refreshInstalled(context)
        return _installed.value.any { it.id == active }
    }

    fun activeModelDir(context: Context): File? {
        val active = _activeModelId.value ?: return null
        return _installed.value.firstOrNull { it.id == active }?.dir
    }

    fun setActive(context: Context, modelId: String?) {
        _activeModelId.value = modelId
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .also { if (modelId == null) it.remove(KEY_ACTIVE) else it.putString(KEY_ACTIVE, modelId) }
            .apply()
        Log.i(TAG, "Active Vosk model set to: $modelId")
    }

    fun delete(context: Context, modelId: String) {
        rootDir(context).resolve(modelId).deleteRecursively()
        if (_activeModelId.value == modelId) setActive(context, null)
        refreshInstalled(context)
    }

    /** Result type for downloads — keeps UI code branch-free. */
    sealed class DownloadResult {
        data class Ok(val installed: Installed) : DownloadResult()
        data class Failed(val reason: String)   : DownloadResult()
    }

    /**
     * Download → checksum → extract. Reports progress (0..100) on the
     * caller's coroutine context. Safe to cancel at any time — partial
     * files are wiped.
     *
     * @param customId  override id (used when downloading a custom URL)
     * @param expectedSha256  optional override; pass null to use [Preset.sha256]
     */
    suspend fun downloadAndInstall(
        context: Context,
        preset: Preset,
        customId: String? = null,
        expectedSha256: String? = preset.sha256,
        onProgress: (Int) -> Unit = {}
    ): DownloadResult = withContext(Dispatchers.IO) {
        val targetId = customId ?: preset.id
        val root = rootDir(context).also { it.mkdirs() }
        val tmpZip = File(root, ".$targetId.tmp")
        val finalDir = File(root, targetId)
        try {
            if (finalDir.exists()) finalDir.deleteRecursively()
            tmpZip.delete()

            // ── 1. Download ─────────────────────────────────────────────
            val req = Request.Builder().url(preset.url).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return@withContext DownloadResult.Failed("HTTP ${resp.code}")
                }
                val body = resp.body ?: return@withContext DownloadResult.Failed("empty body")
                val total = body.contentLength().takeIf { it > 0 } ?: -1L
                body.byteStream().use { input ->
                    FileOutputStream(tmpZip).use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read: Int
                        var copied = 0L
                        var lastReported = -1
                        while (input.read(buf).also { read = it } != -1) {
                            out.write(buf, 0, read)
                            copied += read
                            if (total > 0) {
                                val pct = ((copied * 90) / total).toInt().coerceIn(0, 90)
                                if (pct != lastReported) {
                                    lastReported = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            }

            // ── 2. Checksum ─────────────────────────────────────────────
            if (!expectedSha256.isNullOrBlank()) {
                onProgress(92)
                val actual = sha256Hex(tmpZip)
                if (!actual.equals(expectedSha256, ignoreCase = true)) {
                    tmpZip.delete()
                    return@withContext DownloadResult.Failed(
                        "checksum mismatch: expected $expectedSha256 got $actual"
                    )
                }
            }

            // ── 3. Extract ──────────────────────────────────────────────
            onProgress(95)
            extractZipFlattenTopDir(tmpZip, finalDir)
            tmpZip.delete()

            if (!isValidVoskModel(finalDir)) {
                finalDir.deleteRecursively()
                return@withContext DownloadResult.Failed(
                    "extracted folder is not a valid Vosk model (missing am/ or conf/)"
                )
            }
            onProgress(100)

            refreshInstalled(context)
            val match = _installed.value.firstOrNull { it.id == targetId }
                ?: return@withContext DownloadResult.Failed("post-install lookup failed")
            // Auto-select if nothing is currently active.
            if (_activeModelId.value == null) setActive(context, targetId)
            DownloadResult.Ok(match)
        } catch (t: Throwable) {
            tmpZip.delete()
            finalDir.deleteRecursively()
            Log.w(TAG, "Download failed: ${t.message}", t)
            DownloadResult.Failed(t.message ?: t.javaClass.simpleName)
        }
    }

    /** Loads the currently-active Vosk [Model] off the main thread. */
    suspend fun loadActiveModel(context: Context): Model? = withContext(Dispatchers.IO) {
        val dir = activeModelDir(context) ?: return@withContext null
        try {
            Model(dir.absolutePath)
        } catch (t: Throwable) {
            Log.w(TAG, "Vosk Model() failed for ${dir.absolutePath}: ${t.message}", t)
            null
        }
    }

    // ── helpers ──────────────────────────────────────────────────────────

    private fun rootDir(context: Context): File =
        File(context.applicationContext.filesDir, ROOT_DIR)

    private fun isValidVoskModel(dir: File): Boolean {
        if (!dir.isDirectory) return false
        // Vosk models always contain an `am/` directory and a `conf/` dir.
        return File(dir, "am").isDirectory && File(dir, "conf").isDirectory
    }

    private fun sha256Hex(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            var read: Int
            while (ins.read(buf).also { read = it } != -1) md.update(buf, 0, read)
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * Extracts [zip] into [dest]. Vosk model zips bundle a single top-level
     * directory (e.g. `vosk-model-small-en-us-0.15/...`). We strip that
     * prefix so the extracted layout is `dest/am/...`, `dest/conf/...`, etc.
     */
    private fun extractZipFlattenTopDir(zip: File, dest: File) {
        dest.mkdirs()
        var topPrefix: String? = null
        ZipInputStream(zip.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val name = entry.name.replace('\\', '/')
                if (topPrefix == null) {
                    val firstSlash = name.indexOf('/')
                    topPrefix = if (firstSlash > 0) name.substring(0, firstSlash + 1) else ""
                }
                val rel = if (topPrefix!!.isNotEmpty() && name.startsWith(topPrefix!!))
                    name.substring(topPrefix!!.length)
                else
                    name
                if (rel.isNotEmpty()) {
                    val outFile = File(dest, rel)
                    // ZipSlip protection
                    val canonical = outFile.canonicalPath
                    if (!canonical.startsWith(dest.canonicalPath)) {
                        throw SecurityException("Zip entry escapes destination: $name")
                    }
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zis.copyTo(out, bufferSize = 64 * 1024)
                        }
                    }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }
}
