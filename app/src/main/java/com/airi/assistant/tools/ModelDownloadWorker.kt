package com.airi.assistant.tools

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * WorkManager-backed model download. Replaces the fragile raw-Thread
 * pipeline with a CoroutineWorker that:
 *
 *   • survives process death (WorkManager re-queues us)
 *   • supports HTTP Range resume across restarts (writes to `<name>.part`,
 *     atomic rename on success, never leaves a half-file at the final path)
 *   • cancels promptly through WorkManager's coroutine cancellation —
 *     the inner read loop checks `isStopped` every chunk
 *   • streams progress via setProgress() so any UI subscribed via
 *     [observeWork] sees live byte counts
 *   • verifies SHA-256 when the caller supplies an expected digest
 *
 * The legacy [ModelDownloadService] continues to exist for back-compat
 * (the in-app foreground notification path), but new call sites should
 * route through [DownloadCenter.enqueue] which delegates here.
 *
 * AIRI tags emitted (per spec):
 *   DOWNLOAD_START            – resolved URL + dest + resume offset
 *   DOWNLOAD_RESUMED          – partial existed, range request made
 *   DOWNLOAD_PROGRESS         – every ~1 MB
 *   DOWNLOAD_CANCEL_REQUESTED – isStopped detected mid-stream
 *   DOWNLOAD_CANCELLED        – worker exited via cancellation
 *   DOWNLOAD_VERIFIED         – sha-256 matched (only when expected provided)
 *   DOWNLOAD_VERIFY_FAILED    – sha-256 mismatch — partial file deleted
 *   DOWNLOAD_COMPLETED        – moved into place
 *   DOWNLOAD_WORKER_EXIT      – success/failure + reason
 */
class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val url      = inputData.getString(KEY_URL)      ?: return@withContext fail("missing url")
        val fileName = inputData.getString(KEY_FILENAME) ?: return@withContext fail("missing filename")
        val expectedSha = inputData.getString(KEY_EXPECTED_SHA256) // optional
        val expectedSize = inputData.getLong(KEY_EXPECTED_SIZE, -1L)

        val modelsDir = File(applicationContext.getExternalFilesDir(null), "models").apply { mkdirs() }
        val finalFile = File(modelsDir, fileName)
        val partFile  = File(modelsDir, "$fileName.part")
        val resumeFrom = if (partFile.exists()) partFile.length() else 0L

        Log.i(
            "AIRI",
            "DOWNLOAD_START fileName=$fileName resume_from=$resumeFrom expected=$expectedSize url=$url"
        )

        var lastError: String? = null
        var success = false
        try {
            success = streamWithResume(url, partFile, resumeFrom, expectedSize)
            if (!success) {
                lastError = "stream_failed"
                return@withContext fail("stream failed")
            }
            // Optional integrity check — only done when caller supplied a known SHA.
            if (!expectedSha.isNullOrBlank()) {
                val actual = sha256Of(partFile)
                if (!actual.equals(expectedSha, ignoreCase = true)) {
                    Log.i("AIRI",
                        "DOWNLOAD_VERIFY_FAILED fileName=$fileName expected=$expectedSha actual=$actual")
                    runCatching { partFile.delete() }
                    lastError = "sha_mismatch"
                    return@withContext fail("integrity check failed")
                }
                Log.i("AIRI", "DOWNLOAD_VERIFIED fileName=$fileName sha256=$actual")
            }
            // Atomic move into place.
            if (finalFile.exists()) finalFile.delete()
            if (!partFile.renameTo(finalFile)) {
                lastError = "rename_failed"
                return@withContext fail("rename failed")
            }
            Log.i(
                "AIRI",
                "DOWNLOAD_COMPLETED fileName=$fileName path=${finalFile.absolutePath} sizeBytes=${finalFile.length()}"
            )
            Result.success(workDataOf(
                KEY_RESULT_PATH to finalFile.absolutePath,
                KEY_RESULT_SIZE to finalFile.length()
            ))
        } catch (ce: kotlinx.coroutines.CancellationException) {
            Log.i("AIRI", "DOWNLOAD_CANCELLED fileName=$fileName partial_bytes=${partFile.length()}")
            lastError = "cancelled"
            // CoroutineWorker contract: rethrow CancellationException so the
            // worker is recorded as CANCELLED rather than FAILED.
            throw ce
        } catch (t: Throwable) {
            lastError = t.message ?: t.javaClass.simpleName
            Log.w("AIRI_DOWNLOAD", "doWork failed: $lastError", t)
            return@withContext fail(lastError ?: "unknown")
        } finally {
            Log.i(
                "AIRI",
                "DOWNLOAD_WORKER_EXIT fileName=$fileName success=$success reason=${lastError ?: "ok"}"
            )
        }
    }

    private suspend fun streamWithResume(
        urlString: String,
        partFile: File,
        resumeFrom: Long,
        expectedSize: Long
    ): Boolean {
        val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout    = 30_000
            instanceFollowRedirects = true
            if (resumeFrom > 0) {
                setRequestProperty("Range", "bytes=$resumeFrom-")
            }
        }
        try {
            conn.connect()
            val rc = conn.responseCode
            val resuming = (rc == HttpURLConnection.HTTP_PARTIAL)
            if (rc != HttpURLConnection.HTTP_OK && rc != HttpURLConnection.HTTP_PARTIAL) {
                Log.w("AIRI_DOWNLOAD", "HTTP $rc for $urlString")
                return false
            }
            if (resuming) {
                Log.i("AIRI", "DOWNLOAD_RESUMED fileName=${partFile.name} from=$resumeFrom")
            } else if (resumeFrom > 0) {
                // Server didn't honour Range — restart from scratch.
                runCatching { partFile.delete() }
                Log.i("AIRI", "DOWNLOAD_RESUME_REJECTED fileName=${partFile.name} server_rc=$rc")
            }
            val input = conn.inputStream
            val out   = RandomAccessFile(partFile, "rw")
            try {
                if (resuming) out.seek(resumeFrom)
                val buf = ByteArray(64 * 1024)
                var totalWritten = if (resuming) resumeFrom else 0L
                var sinceLastReport = 0L
                while (true) {
                    if (isStopped) {
                        Log.i("AIRI",
                            "DOWNLOAD_CANCEL_REQUESTED fileName=${partFile.name} written=$totalWritten")
                        return false
                    }
                    val n = input.read(buf)
                    if (n <= 0) break
                    out.write(buf, 0, n)
                    totalWritten += n
                    sinceLastReport += n
                    if (sinceLastReport >= 1L * 1024 * 1024) {
                        val pct = if (expectedSize > 0) ((totalWritten * 100) / expectedSize).toInt() else -1
                        setProgress(workDataOf(
                            KEY_PROGRESS_BYTES to totalWritten,
                            KEY_PROGRESS_TOTAL to expectedSize,
                            KEY_PROGRESS_PCT   to pct
                        ))
                        Log.i("AIRI",
                            "DOWNLOAD_PROGRESS fileName=${partFile.name} bytes=$totalWritten pct=$pct")
                        sinceLastReport = 0L
                    }
                }
                if (expectedSize > 0 && totalWritten < expectedSize) {
                    Log.w("AIRI_DOWNLOAD",
                        "short read: $totalWritten/$expectedSize for ${partFile.name}")
                    return false
                }
                return true
            } finally {
                runCatching { out.close() }
                runCatching { input.close() }
            }
        } finally {
            runCatching { conn.disconnect() }
        }
    }

    private fun sha256Of(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { ins ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = ins.read(buf); if (n <= 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fail(reason: String): Result =
        Result.failure(workDataOf(KEY_RESULT_REASON to reason))

    companion object {
        private const val UNIQUE_PREFIX = "airi-download-"
        const val KEY_URL              = "url"
        const val KEY_FILENAME         = "fileName"
        const val KEY_EXPECTED_SIZE    = "expectedSize"
        const val KEY_EXPECTED_SHA256  = "expectedSha256"
        const val KEY_PROGRESS_BYTES   = "progressBytes"
        const val KEY_PROGRESS_TOTAL   = "progressTotal"
        const val KEY_PROGRESS_PCT     = "progressPct"
        const val KEY_RESULT_PATH      = "resultPath"
        const val KEY_RESULT_SIZE      = "resultSize"
        const val KEY_RESULT_REASON    = "resultReason"

        fun uniqueWorkName(fileName: String) = UNIQUE_PREFIX + fileName

        /**
         * Enqueue a download. KEEP existing work for the same fileName so
         * the user tapping "Download" twice doesn't restart from byte 0
         * and discard the partial. Returns the unique work name.
         */
        fun enqueue(
            context: Context,
            url: String,
            fileName: String,
            expectedSize: Long = -1,
            expectedSha256: String? = null
        ): String {
            val data: Data = workDataOf(
                KEY_URL              to url,
                KEY_FILENAME         to fileName,
                KEY_EXPECTED_SIZE    to expectedSize,
                KEY_EXPECTED_SHA256  to expectedSha256
            )
            val req = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                .setInputData(data)
                .build()
            val name = uniqueWorkName(fileName)
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name, ExistingWorkPolicy.KEEP, req)
            return name
        }

        /** Cancel the unique download. Partial .part file is preserved for resume. */
        fun cancel(context: Context, fileName: String) {
            WorkManager.getInstance(context)
                .cancelUniqueWork(uniqueWorkName(fileName))
            Log.i("AIRI", "DOWNLOAD_CANCEL_REQUESTED source=workmanager fileName=$fileName")
        }

        /** True if this fileName has an active or enqueued worker. */
        suspend fun isActive(context: Context, fileName: String): Boolean {
            val infos = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(uniqueWorkName(fileName))
                .get()
            return infos.any {
                it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED
            }
        }
    }
}
