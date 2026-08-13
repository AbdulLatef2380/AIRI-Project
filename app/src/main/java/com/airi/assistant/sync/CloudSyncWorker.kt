package com.airi.assistant.sync

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.TimeUnit

/**
 * CloudSyncWorker — WorkManager periodic worker that drives background
 * profile and memory sync via [CloudSyncCoordinator].
 *
 * ── SCHEDULE ──────────────────────────────────────────────────────────────
 *
 *   Repeats every [SYNC_PERIOD_HOURS] hours (default: 6h). Requires network.
 *   Uses exponential backoff on failure (max 4h).
 *
 * ── WHAT IT SYNCS ─────────────────────────────────────────────────────────
 *
 *   1. UserPreferences → Firestore (push)
 *   2. Firestore       → UserPreferences (pull / merge)
 *
 *   Memory entries are NOT synced here (they are large). Memory sync is a
 *   future opt-in feature gated behind a separate preference flag.
 *
 * ── ENQUEUE ───────────────────────────────────────────────────────────────
 *
 *   Call [enqueue] from ServiceLocator / AIRIApplication once sync is
 *   enabled. Uses ExistingPeriodicWorkPolicy.UPDATE so re-enqueueing with a
 *   new period picks up immediately.
 */
class CloudSyncWorker(
    appContext: Context,
    params:     WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        Log.i(TAG, "CloudSyncWorker starting attempt=$runAttemptCount")

        val prefs = ServiceLocator.userProfileRepository.current
        if (!prefs.cloudSyncEnabled) {
            LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_WORKER_SKIPPED — sync disabled")
            return Result.success()
        }

        return runCatching {
            ServiceLocator.cloudSyncCoordinator.pull()
            ServiceLocator.cloudSyncCoordinator.push()
            LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_WORKER_OK")
            Result.success()
        }.getOrElse { e ->
            Log.w(TAG, "CloudSyncWorker failed: ${e.message}")
            if (runAttemptCount < MAX_RETRIES) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val TAG          = "CloudSyncWorker"
        private const val WORK_NAME    = "airi_cloud_sync"
        private const val MAX_RETRIES  = 3
        const val SYNC_PERIOD_HOURS    = 6L

        fun enqueue(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<CloudSyncWorker>(
                SYNC_PERIOD_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.MINUTES)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request
            )
            LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_WORKER_ENQUEUED period=${SYNC_PERIOD_HOURS}h")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LoggingService.info(TAG, "AIRI_RUNTIME CLOUD_SYNC_WORKER_CANCELLED")
        }
    }
}
