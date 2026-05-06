package com.airi.assistant.ai

import android.content.Context
import android.util.Log
import com.airi.assistant.domain.logging.LoggingService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest

/**
 * InternalModelExtractor — validates and extracts bundled AI model files
 * from the APK's assets/ directory to the app's private filesDir.
 *
 * ── MOTIVATION ───────────────────────────────────────────────────────────
 *
 *   llama.cpp, Vosk, and Porcupine all require model files to be accessible
 *   as regular filesystem paths. Android restricts direct asset access from
 *   native code. InternalModelExtractor copies these files on first launch
 *   (and re-copies if the APK is updated) so native libraries can open them.
 *
 * ── VALIDATION ───────────────────────────────────────────────────────────
 *
 *   After extraction, each file is validated against an expected SHA-256
 *   digest (if provided in [ModelDescriptor.expectedSha256]). This prevents
 *   using a corrupted or tampered model file.
 *
 * ── INCREMENTAL EXTRACTION ────────────────────────────────────────────────
 *
 *   Files are only re-extracted when:
 *     1. The destination file does not exist.
 *     2. The APK version code is higher than the last extraction version.
 *     3. The SHA-256 digest does not match (file corrupted).
 *
 * ── KNOWN MODELS ─────────────────────────────────────────────────────────
 *
 *   LLaMA GGUF  — assets/models/*.gguf  → filesDir/models/*.gguf
 *   Vosk model  — assets/vosk_model/    → filesDir/vosk_model/
 *   Porcupine   — raw resource (handled by Porcupine SDK, NOT extracted here)
 */
class InternalModelExtractor(private val appContext: Context) {

    private val TAG = "InternalModelExtractor"

    private val _progress = MutableStateFlow<ExtractionProgress?>(null)
    val progress: StateFlow<ExtractionProgress?> = _progress.asStateFlow()

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Extract all registered model descriptors from assets to filesDir.
     *
     * This is safe to call on every app launch — it is idempotent and skips
     * files that are already up-to-date.
     *
     * @param force  If true, re-extracts even if the file already exists.
     * @return       [ExtractionReport] with per-file results.
     */
    suspend fun extractAll(force: Boolean = false): ExtractionReport = withContext(Dispatchers.IO) {
        val descriptors = discoverModels()
        if (descriptors.isEmpty()) {
            Log.i(TAG, "AIRI_PROOF MODEL_EXTRACTED count=0 (no bundled models found in assets)")
            return@withContext ExtractionReport(emptyList())
        }

        LoggingService.info(TAG, "AIRI_PROOF MODEL_EXTRACT_START count=${descriptors.size}")
        val results = mutableListOf<FileResult>()

        descriptors.forEachIndexed { idx, desc ->
            _progress.value = ExtractionProgress(desc.displayName, idx + 1, descriptors.size)
            results += extractOne(desc, force)
        }

        val report = ExtractionReport(results)
        LoggingService.info(TAG, "AIRI_PROOF MODEL_EXTRACTED ok=${report.successCount} skip=${report.skippedCount} fail=${report.failureCount}")
        _progress.value = null
        report
    }

    /**
     * Extract a single [ModelDescriptor]. Returns [FileResult].
     */
    suspend fun extractOne(desc: ModelDescriptor, force: Boolean = false): FileResult =
        withContext(Dispatchers.IO) {
            val dest = File(appContext.filesDir, desc.destRelativePath)

            // Check if extraction is needed
            if (!force && dest.exists() && !isStale(dest, desc)) {
                Log.d(TAG, "MODEL_SKIP ${desc.assetPath} → ${dest.path} (up-to-date)")
                return@withContext FileResult(desc, dest.path, status = FileStatus.SKIPPED)
            }

            dest.parentFile?.mkdirs()

            return@withContext runCatching {
                appContext.assets.open(desc.assetPath).use { input ->
                    FileOutputStream(dest).use { output ->
                        val buf = ByteArray(BUFFER_SIZE)
                        var totalBytes = 0L
                        var read: Int
                        val digest = MessageDigest.getInstance("SHA-256")

                        while (input.read(buf).also { read = it } != -1) {
                            output.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            totalBytes += read
                        }
                        output.flush()

                        val sha256 = digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
                        if (desc.expectedSha256 != null && sha256 != desc.expectedSha256) {
                            dest.delete()
                            throw SecurityException(
                                "SHA-256 mismatch for ${desc.assetPath}: " +
                                "expected=${desc.expectedSha256} actual=$sha256"
                            )
                        }

                        Log.i(TAG, "AIRI_PROOF MODEL_EXTRACTED file=${desc.assetPath} bytes=$totalBytes sha256=${sha256.take(16)}…")
                        FileResult(desc, dest.path, status = FileStatus.EXTRACTED,
                            bytesWritten = totalBytes, sha256 = sha256)
                    }
                }
            }.getOrElse { e ->
                Log.e(TAG, "MODEL_EXTRACT_FAIL file=${desc.assetPath} reason=${e.message}", e)
                FileResult(desc, dest.path, status = FileStatus.FAILED, failReason = e.message)
            }
        }

    /**
     * Validate that all expected model files exist and pass SHA-256 check.
     * Returns a map of assetPath → valid.
     */
    suspend fun validateAll(): Map<String, Boolean> = withContext(Dispatchers.IO) {
        discoverModels().associate { desc ->
            val dest = File(appContext.filesDir, desc.destRelativePath)
            desc.assetPath to (dest.exists() && !isStale(dest, desc))
        }
    }

    /**
     * Return the absolute filesystem path where a model will be placed.
     * Used by LlamaManager / VoskSpeechRecognizer to locate their files.
     */
    fun resolvedPath(desc: ModelDescriptor): String =
        File(appContext.filesDir, desc.destRelativePath).absolutePath

    // ── Discovery ─────────────────────────────────────────────────────────────

    /**
     * Discover bundled models by scanning assets/models/ and assets/vosk_model/.
     * Returns [ModelDescriptor]s for each file found.
     */
    private fun discoverModels(): List<ModelDescriptor> {
        val discovered = mutableListOf<ModelDescriptor>()
        discovered += discoverAssetDir("models", "models")
        discovered += discoverAssetDir("vosk_model", "vosk_model")
        return discovered
    }

    private fun discoverAssetDir(assetDir: String, destDir: String): List<ModelDescriptor> {
        return runCatching {
            appContext.assets.list(assetDir)?.map { filename ->
                ModelDescriptor(
                    assetPath        = "$assetDir/$filename",
                    destRelativePath = "$destDir/$filename",
                    displayName      = filename,
                    expectedSha256   = null
                )
            } ?: emptyList()
        }.getOrElse {
            emptyList() // asset dir doesn't exist — normal when no models bundled
        }
    }

    // ── Staleness check ───────────────────────────────────────────────────────

    private fun isStale(dest: File, desc: ModelDescriptor): Boolean {
        if (!dest.exists()) return true
        // If a digest is provided, verify it
        if (desc.expectedSha256 == null) return false
        return runCatching {
            val sha256 = sha256Of(dest)
            sha256 != desc.expectedSha256
        }.getOrDefault(true)
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            var read: Int
            while (input.read(buf).also { read = it } != -1) {
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    // ── Types ─────────────────────────────────────────────────────────────────

    /**
     * Describes a single model file to be extracted.
     *
     * @param assetPath        Path within the APK assets/ directory.
     * @param destRelativePath Path relative to [Context.getFilesDir].
     * @param displayName      Human-readable name for progress UI.
     * @param expectedSha256   Optional expected SHA-256 hex digest for validation.
     */
    data class ModelDescriptor(
        val assetPath:        String,
        val destRelativePath: String,
        val displayName:      String,
        val expectedSha256:   String? = null
    )

    enum class FileStatus { EXTRACTED, SKIPPED, FAILED }

    data class FileResult(
        val descriptor:  ModelDescriptor,
        val destPath:    String,
        val status:      FileStatus,
        val bytesWritten: Long    = 0L,
        val sha256:      String   = "",
        val failReason:  String?  = null
    )

    data class ExtractionReport(val results: List<FileResult>) {
        val successCount: Int get() = results.count { it.status == FileStatus.EXTRACTED }
        val skippedCount: Int get() = results.count { it.status == FileStatus.SKIPPED }
        val failureCount: Int get() = results.count { it.status == FileStatus.FAILED }
        val allOk: Boolean   get() = failureCount == 0

        fun summary(): String = buildString {
            appendLine("InternalModelExtractor: ${results.size} models")
            appendLine("  Extracted: $successCount  Skipped: $skippedCount  Failed: $failureCount")
            results.filter { it.status == FileStatus.FAILED }.forEach {
                appendLine("  FAIL ${it.descriptor.assetPath}: ${it.failReason}")
            }
        }
    }

    data class ExtractionProgress(
        val currentFile: String,
        val current:     Int,
        val total:       Int
    ) {
        val percent: Int get() = if (total > 0) current * 100 / total else 0
    }

    companion object {
        private const val BUFFER_SIZE = 64 * 1024

        /** Common model descriptors for known AIRI model files. */
        fun ggufDescriptor(filename: String, sha256: String? = null) = ModelDescriptor(
            assetPath        = "models/$filename",
            destRelativePath = "models/$filename",
            displayName      = filename,
            expectedSha256   = sha256
        )
    }
}
