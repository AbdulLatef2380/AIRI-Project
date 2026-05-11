package com.airi.assistant.ai

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * ModelMarketplace — curated model catalog + download orchestrator.
 *
 * Provides the full marketplace surface consumed by the Model Gallery UI:
 *  - [catalogEntries] — curated list of downloadable GGUF models, filtered
 *    by device RAM so only viable options are surfaced.
 *  - [download] — streams a GGUF file from HuggingFace into the app's
 *    dedicated models directory, reporting progress via [ModelManager].
 *  - [scanDevice] — scans external and internal storage directories for
 *    existing .gguf files and registers them as local models.
 *  - [delete] — removes a downloaded model file from storage and
 *    unregisters it from ModelManager.
 *
 * ## Thread safety
 * All download operations run on [Dispatchers.IO]. Progress is emitted
 * through [ModelManager.downloadStates] StateFlow which is safe to collect
 * from any thread.
 *
 * ## File storage
 * Models are written to [Context.getExternalFilesDir("models")] (preferred
 * for large files) with a fallback to [Context.filesDir]/models when
 * external storage is unavailable.
 */
class ModelMarketplace(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.MINUTES)   // GGUF files can be many GB
        .build()

    // ── Catalog ───────────────────────────────────────────────────────────────

    data class CatalogEntry(
        val id:           String,
        val name:         String,
        val description:  String,
        val downloadUrl:  String,
        val sizeBytes:    Long,
        val ramRequiredMb: Int,
        val quantization: String,
        val contextSize:  Int       = 4096,
        val modelType:    ModelType = ModelType.QWEN,
        val arch:         String    = "qwen2",
        val tags:         List<String> = emptyList(),
        val isRecommended: Boolean  = false
    ) {
        val sizeMb: Int get() = (sizeBytes / (1024 * 1024)).toInt()
        val sizeDisplay: String get() = when {
            sizeBytes >= 1_000_000_000L -> String.format("%.1f GB", sizeBytes / 1_000_000_000.0)
            else                        -> "${sizeMb} MB"
        }
    }

    /** Full curated catalog — all models AIRI supports downloading. */
    private val fullCatalog: List<CatalogEntry> = listOf(
        // ── Qwen3 series ─────────────────────────────────────────────────────
        CatalogEntry(
            id            = "qwen3_0_6b_q4",
            name          = "Qwen3 0.6B Q4_K_M",
            description   = "Ultra-lightweight Qwen3 — ideal for low-end devices. Fast and responsive.",
            downloadUrl   = "https://huggingface.co/Qwen/Qwen3-0.6B-GGUF/resolve/main/qwen3-0.6b-q4_k_m.gguf",
            sizeBytes     = 420_000_000L,
            ramRequiredMb = 800,
            quantization  = "Q4_K_M",
            contextSize   = 4096,
            modelType     = ModelType.QWEN,
            arch          = "qwen3",
            tags          = listOf("fast", "small", "arabic"),
            isRecommended = true
        ),
        CatalogEntry(
            id            = "qwen3_1_7b_q4",
            name          = "Qwen3 1.7B Q4_K_M",
            description   = "Compact Qwen3 with excellent Arabic/English balance.",
            downloadUrl   = "https://huggingface.co/Qwen/Qwen3-1.7B-GGUF/resolve/main/qwen3-1.7b-q4_k_m.gguf",
            sizeBytes     = 1_100_000_000L,
            ramRequiredMb = 1_800,
            quantization  = "Q4_K_M",
            contextSize   = 8192,
            modelType     = ModelType.QWEN,
            arch          = "qwen3",
            tags          = listOf("balanced", "arabic", "multilingual"),
            isRecommended = true
        ),
        CatalogEntry(
            id            = "qwen3_4b_q4",
            name          = "Qwen3 4B Q4_K_M",
            description   = "Mid-range Qwen3 with strong reasoning and Arabic fluency.",
            downloadUrl   = "https://huggingface.co/Qwen/Qwen3-4B-GGUF/resolve/main/qwen3-4b-q4_k_m.gguf",
            sizeBytes     = 2_600_000_000L,
            ramRequiredMb = 3_500,
            quantization  = "Q4_K_M",
            contextSize   = 16384,
            modelType     = ModelType.QWEN,
            arch          = "qwen3",
            tags          = listOf("reasoning", "arabic", "agent")
        ),
        CatalogEntry(
            id            = "qwen3_8b_q4",
            name          = "Qwen3 8B Q4_K_M",
            description   = "Full Qwen3 8B — best quality on flagship Android devices.",
            downloadUrl   = "https://huggingface.co/Qwen/Qwen3-8B-GGUF/resolve/main/qwen3-8b-q4_k_m.gguf",
            sizeBytes     = 5_200_000_000L,
            ramRequiredMb = 7_000,
            quantization  = "Q4_K_M",
            contextSize   = 32768,
            modelType     = ModelType.QWEN,
            arch          = "qwen3",
            tags          = listOf("flagship", "reasoning", "arabic", "agent")
        ),
        // ── Gemma 3 series ────────────────────────────────────────────────────
        CatalogEntry(
            id            = "gemma3_1b_q4",
            name          = "Gemma 3 1B Q4_K_M",
            description   = "Google's Gemma 3 1B — very fast, minimal memory footprint.",
            downloadUrl   = "https://huggingface.co/google/gemma-3-1b-it-GGUF/resolve/main/gemma-3-1b-it-Q4_K_M.gguf",
            sizeBytes     = 800_000_000L,
            ramRequiredMb = 1_200,
            quantization  = "Q4_K_M",
            contextSize   = 8192,
            modelType     = ModelType.GEMMA,
            arch          = "gemma3",
            tags          = listOf("fast", "google", "instruction"),
            isRecommended = false
        ),
        CatalogEntry(
            id            = "gemma3_4b_q4",
            name          = "Gemma 3 4B Q4_K_M",
            description   = "Google's Gemma 3 4B instruction-tuned — strong coding and reasoning.",
            downloadUrl   = "https://huggingface.co/google/gemma-3-4b-it-GGUF/resolve/main/gemma-3-4b-it-Q4_K_M.gguf",
            sizeBytes     = 2_800_000_000L,
            ramRequiredMb = 4_000,
            quantization  = "Q4_K_M",
            contextSize   = 8192,
            modelType     = ModelType.GEMMA,
            arch          = "gemma3",
            tags          = listOf("coding", "google", "reasoning")
        ),
        // ── LLaMA 3 series ───────────────────────────────────────────────────
        CatalogEntry(
            id            = "llama3_2_1b_q4",
            name          = "LLaMA 3.2 1B Q4_K_M",
            description   = "Meta's tiny LLaMA 3.2 — excellent for quick assistant tasks.",
            downloadUrl   = "https://huggingface.co/meta-llama/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf",
            sizeBytes     = 770_000_000L,
            ramRequiredMb = 1_200,
            quantization  = "Q4_K_M",
            contextSize   = 4096,
            modelType     = ModelType.LLAMA,
            arch          = "llama",
            tags          = listOf("fast", "meta", "instruction")
        ),
        CatalogEntry(
            id            = "llama3_2_3b_q4",
            name          = "LLaMA 3.2 3B Q4_K_M",
            description   = "Meta's LLaMA 3.2 3B — strong instruction following.",
            downloadUrl   = "https://huggingface.co/meta-llama/Llama-3.2-3B-Instruct-GGUF/resolve/main/Llama-3.2-3B-Instruct-Q4_K_M.gguf",
            sizeBytes     = 2_000_000_000L,
            ramRequiredMb = 2_800,
            quantization  = "Q4_K_M",
            contextSize   = 4096,
            modelType     = ModelType.LLAMA,
            arch          = "llama",
            tags          = listOf("meta", "instruction", "balanced")
        )
    )

    // ── Filtered catalog (device-appropriate) ─────────────────────────────────

    private val _catalogEntries = MutableStateFlow<List<CatalogEntry>>(emptyList())
    val catalogEntries: StateFlow<List<CatalogEntry>> = _catalogEntries.asStateFlow()

    init {
        scope.launch { refreshCatalog() }
    }

    private fun refreshCatalog() {
        val availRam = ModelManager.availableRamMb()
        _catalogEntries.value = fullCatalog.filter { entry ->
            entry.arch in SUPPORTED_ARCHS &&
            (entry.ramRequiredMb <= 0 || entry.ramRequiredMb.toLong() <= availRam)
        }
        Log.i(TAG, "Catalog refreshed: ${_catalogEntries.value.size}/${fullCatalog.size} entries visible (availRam=${availRam}MB)")
    }

    /** The full un-filtered catalog — for the "all models" list in the Store. */
    fun getFullCatalog(): List<CatalogEntry> = fullCatalog

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * Download a model from [entry.downloadUrl] to the device's models directory.
     * Progress is streamed via [ModelManager.downloadStates].
     * Returns the local [File] on success, or null on failure.
     */
    suspend fun download(
        entry:      CatalogEntry,
        onComplete: (ModelInfo?) -> Unit = {}
    ): File? {
        val modelId  = entry.id
        val destFile = resolveModelFile(entry.id, entry.downloadUrl)

        if (destFile.exists() && destFile.length() > 0L) {
            Log.i(TAG, "DOWNLOAD_SKIP already exists: ${destFile.name}")
            val info = buildModelInfo(entry, destFile)
            ModelManager.addModel(info)
            onComplete(info)
            return destFile
        }

        ModelManager.beginDownload(modelId, entry.sizeBytes)
        Log.i(TAG, "DOWNLOAD_START id=$modelId url=${entry.downloadUrl}")

        return try {
            val request  = Request.Builder().url(entry.downloadUrl).build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val msg = "HTTP ${response.code}"
                ModelManager.failDownload(modelId, msg)
                onComplete(null)
                return null
            }

            val body      = response.body ?: run {
                ModelManager.failDownload(modelId, "Empty response body")
                onComplete(null)
                return null
            }
            val totalLen  = body.contentLength().coerceAtLeast(entry.sizeBytes)
            destFile.parentFile?.mkdirs()

            body.byteStream().use { input ->
                FileOutputStream(destFile).use { output ->
                    val buf      = ByteArray(128 * 1024)   // 128 KB chunks
                    var loaded   = 0L
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        loaded += read
                        ModelManager.updateDownloadProgress(modelId, loaded, totalLen)
                    }
                }
            }

            ModelManager.completeDownload(modelId)
            val info = buildModelInfo(entry, destFile)
            ModelManager.addModel(info)
            Log.i(TAG, "DOWNLOAD_COMPLETE id=$modelId path=${destFile.absolutePath}")
            onComplete(info)
            destFile
        } catch (e: Exception) {
            Log.e(TAG, "DOWNLOAD_ERROR id=$modelId: ${e.message}")
            destFile.takeIf { it.exists() }?.delete()
            ModelManager.failDownload(modelId, e.message ?: "Unknown error")
            onComplete(null)
            null
        }
    }

    // ── Device scan ───────────────────────────────────────────────────────────

    /**
     * Scans common GGUF locations on the device and registers found files.
     * Returns the list of newly registered [ModelInfo]s.
     */
    fun scanDevice(): List<ModelInfo> {
        val scanDirs = buildList {
            context.getExternalFilesDir("models")?.let { add(it) }
            add(context.filesDir.resolve("models"))
            context.getExternalFilesDirs(null).forEach { dir ->
                if (dir != null) {
                    add(File(dir, "AIRI/models"))
                    add(File(dir, "Download"))
                    add(File(dir, "Documents"))
                }
            }
        }

        val found = mutableListOf<ModelInfo>()
        val existingPaths = ModelManager.getAllModels().map { it.path }.toSet()

        for (dir in scanDirs) {
            if (!dir.exists() || !dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
                .forEach { file ->
                    if (file.absolutePath !in existingPaths) {
                        val info = ModelInfo(
                            name         = file.nameWithoutExtension
                                .replace("_", " ")
                                .replace("-", " ")
                                .replaceFirstChar { it.uppercase() },
                            fileName     = file.name,
                            size         = file.length(),
                            quantization = parseQuantization(file.name),
                            path         = file.absolutePath,
                            source       = ModelSource.LOCAL_FILE,
                            type         = ModelType.inferFromFileName(file.name),
                            isLocal      = true
                        )
                        ModelManager.addModel(info)
                        found += info
                        Log.i(TAG, "SCAN_FOUND ${file.absolutePath}")
                    }
                }
        }
        Log.i(TAG, "SCAN_COMPLETE found=${found.size} new models")
        return found
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Remove a model from AIRI: unloads if active, deletes the GGUF file
     * (only if it lives inside the app's models directory), and unregisters
     * from ModelManager.
     */
    fun delete(model: ModelInfo) {
        val file = File(model.path)
        val isOwnedByApp = file.absolutePath.startsWith(
            (context.getExternalFilesDir("models") ?: context.filesDir.resolve("models"))
                .absolutePath
        )
        if (isOwnedByApp && file.exists()) {
            val deleted = file.delete()
            Log.i(TAG, "DELETE_FILE path=${file.absolutePath} success=$deleted")
        }
        ModelManager.remove(model)
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun resolveModelFile(modelId: String, url: String): File {
        val fileName = url.substringAfterLast("/").ifBlank { "$modelId.gguf" }
        val dir      = context.getExternalFilesDir("models")
            ?: context.filesDir.resolve("models")
        return File(dir, fileName)
    }

    private fun buildModelInfo(entry: CatalogEntry, file: File): ModelInfo = ModelInfo(
        id           = entry.id,
        name         = entry.name,
        fileName     = file.name,
        size         = file.length(),
        quantization = entry.quantization,
        path         = file.absolutePath,
        source       = ModelSource.DOWNLOADED,
        type         = entry.modelType,
        isLocal      = true,
        ramRequiredMb = entry.ramRequiredMb,
        contextSize  = entry.contextSize
    )

    private fun parseQuantization(fileName: String): String {
        val upper = fileName.uppercase()
        val patterns = listOf("Q8_0", "Q6_K", "Q5_K_M", "Q5_K_S", "Q5_0",
            "Q4_K_M", "Q4_K_S", "Q4_0", "Q3_K_M", "Q3_K_S", "Q2_K",
            "F16", "F32", "IQ4_XS", "IQ3_M", "IQ2_M")
        return patterns.firstOrNull { it in upper } ?: "UNKNOWN"
    }

    companion object {
        private const val TAG = "AIRI_ModelMarketplace"
    }
}
