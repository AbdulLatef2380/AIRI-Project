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
import com.airi.assistant.domain.background.BackgroundWorkNames
import kotlinx.coroutines.CancellationException
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
 *   3. Opt-in durable long-term-memory outbox → Firestore (push)
 *   4. Owner-scoped Firestore memory tombstones/rows → local memory (pull)
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
            LoggingService.info(TAG, "AIRI CLOUD_SYNC_WORKER_SKIPPED — sync disabled")
            return Result.success()
        }

        return try {
            suspend fun runOperation(
                name: String,
                operation: suspend () -> CloudSyncCoordinator.SyncResult,
            ): Boolean = when (operation()) {
                CloudSyncCoordinator.SyncResult.SUCCESS,
                CloudSyncCoordinator.SyncResult.SKIPPED -> true
                CloudSyncCoordinator.SyncResult.FAILED -> {
                    LoggingService.warn(TAG, "AIRI CLOUD_SYNC_WORKER_RETRY operation=$name")
                    false
                }
            }
            if (!runOperation("profile_pull") { ServiceLocator.cloudSyncCoordinator.pull() }) return retryOrFailure()
            if (!runOperation("memory_pull") { ServiceLocator.cloudSyncCoordinator.pullMemories(ServiceLocator.memoryManager) }) return retryOrFailure()
            if (!runOperation("task_continuity_pull") { ServiceLocator.cloudSyncCoordinator.pullTaskContinuity(ServiceLocator.durableTaskManager) }) return retryOrFailure()
            if (!runOperation("profile_push") { ServiceLocator.cloudSyncCoordinator.push() }) return retryOrFailure()
            if (!runOperation("memory_push") { ServiceLocator.cloudSyncCoordinator.pushMemories(ServiceLocator.memoryManager) }) return retryOrFailure()
            if (!runOperation("task_continuity_push") { ServiceLocator.cloudSyncCoordinator.pushTaskContinuity(ServiceLocator.durableTaskManager) }) return retryOrFailure()
            LoggingService.info(TAG, "AIRI CLOUD_SYNC_WORKER_OK")
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "CloudSyncWorker failed type=${e.javaClass.simpleName}")
            if (CloudSyncRetryPolicy.shouldRetry(runAttemptCount)) Result.retry() else Result.failure()
        }
    }

    private fun retryOrFailure(): Result =
        if (CloudSyncRetryPolicy.shouldRetry(runAttemptCount)) Result.retry() else Result.failure()

    companion object {
        private const val TAG          = "CloudSyncWorker"
        private const val WORK_NAME    = BackgroundWorkNames.CLOUD_SYNC
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
            LoggingService.info(TAG, "AIRI CLOUD_SYNC_WORKER_ENQUEUED period=${SYNC_PERIOD_HOURS}h")
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LoggingService.info(TAG, "AIRI CLOUD_SYNC_WORKER_CANCELLED")
        }
    }
}
