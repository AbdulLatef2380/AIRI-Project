package com.airi.assistant.ai.agent.background

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.airi.assistant.ai.intent.ToolCall
import com.airi.assistant.ai.tools.ToolExecutor
import com.airi.assistant.auth.SecureStorage
import com.airi.assistant.core.ServiceLocator
import com.airi.assistant.domain.error.AppErrorHandler
import com.airi.assistant.domain.logging.LoggingService
import java.util.concurrent.TimeUnit

class AgentWorker(
    private val appContext: Context,
    workerParams: WorkerParameters
) : CoroutineWorker(appContext, workerParams) {

    companion object {
        private const val TAG = "AgentWorker"
        private const val WORK_NAME             = "airi_background_agent"
        private const val REPEAT_INTERVAL_HOURS = 2L

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<AgentWorker>(
                REPEAT_INTERVAL_HOURS, TimeUnit.HOURS
            )
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }

    override suspend fun doWork(): Result {
        LoggingService.debug(TAG, "Background agent started")

        // Check network via NetworkService before doing any work
        val networkService = runCatching { ServiceLocator.networkService }.getOrNull()
        if (networkService != null && !networkService.isOnline()) {
            LoggingService.warn(TAG, "No network — skipping background agent run")
            saveSummary("No internet connection — skipped.")
            return Result.success()
        }

        val prefs         = appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
        val secureStorage = SecureStorage(appContext)
        val toolExecutor  = ToolExecutor(appContext)
        val findings      = mutableListOf<String>()

        if (!secureStorage.isGithubConnected() && !secureStorage.isGoogleConnected()) {
            saveSummary("No integrations connected.")
            return Result.success()
        }

        // ── Check GitHub for updates ─────────────────────────────────────────
        if (secureStorage.isGithubConnected()) {
            runCatching {
                val result = toolExecutor.execute(ToolCall("github_get_user", emptyMap()))
                if (result.success && result.data.isNotBlank()) {
                    findings.add("GitHub: ${result.data.lines().firstOrNull() ?: "Active"}")
                }
            }.onFailure { e ->
                AppErrorHandler.capture(e, "AgentWorker.github")
            }
        }

        // ── Check Gmail for important messages ───────────────────────────────
        if (secureStorage.isGoogleConnected()) {
            runCatching {
                val result = toolExecutor.execute(
                    ToolCall("gmail_list_emails", mapOf("max" to "3"))
                )
                if (result.success && result.data.isNotBlank()) {
                    findings.add("Gmail: ${result.data.take(100)}")
                }
            }.onFailure { e ->
                AppErrorHandler.capture(e, "AgentWorker.gmail")
            }
        }

        val summary = if (findings.isEmpty()) "Checked — no updates found."
                      else findings.joinToString(" | ")

        LoggingService.debug(TAG, "Background agent complete: $summary")
        saveSummary(summary)
        return Result.success()
    }

    private fun saveSummary(summary: String) {
        appContext.getSharedPreferences("airi_ui_state", Context.MODE_PRIVATE)
            .edit()
            .putLong("bg_agent_last_run", System.currentTimeMillis())
            .putString("bg_agent_last_result", summary.take(200))
            .apply()
    }
}
