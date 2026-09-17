package com.airi.assistant.resources

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class ResourceMonitorWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        runCatching { ResourceBudgetManager(applicationContext).notifyIfNeeded() }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "airi-resource-monitor"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ResourceMonitorWorker>(6, TimeUnit.HOURS).build()
            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
